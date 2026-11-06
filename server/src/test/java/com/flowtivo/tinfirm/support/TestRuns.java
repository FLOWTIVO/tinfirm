package com.flowtivo.tinfirm.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Run rows written directly, as the runtime role in the company's tenant context (TEST FIXTURE ONLY): for the model
 * relay's tests, which need a run RUNNING on a host with an epoch, or ended, without a conversation to start it, and
 * for the database's own checks of runs. The run module's {@code RunModelTokens} answers from these rows as from any
 * other. Each call commits.
 */
public final class TestRuns {

    private TestRuns() {
    }

    /**
     * The run is RUNNING on the host with the epoch (and on no other host or epoch), paid by the principal's budget;
     * the row is made when the run does not exist yet, with a bare conversation of its own: the payer's own
     * ({@link TestConversations#bare}) when the payer is a member, else a schedule's of a member of the company paid by
     * the service identity ({@link TestTriggers}): a run is paid as its conversation is (V011, T6).
     */
    public static void running(UUID companyId, UUID runId, long epoch, UUID runnerHostId, UUID payerPrincipalId) {
        Instant now = Instant.now();
        UUID agentId = agent(companyId);
        UUID conversationId = UUID.randomUUID();
        String key = "relay-" + conversationId.toString().substring(0, 8);
        UUID acting = payerPrincipalId;
        UUID triggerId = null;
        if (service(companyId, payerPrincipalId)) {
            acting = TestConversations.anyMember(companyId);
            triggerId = TestTriggers.schedule(companyId, agentId, acting, payerPrincipalId);
            TestTriggers.conversation(companyId, conversationId, key, triggerId, agentId, acting, payerPrincipalId);
        }
        else {
            TestConversations.bare(companyId, conversationId, key, agentId, payerPrincipalId, "CLAUDE_CODE");
        }
        UUID actingPrincipalId = acting;
        UUID trigger = triggerId;
        execute(companyId, """
                INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                                 turn_number, acting_principal_id, payer_principal_id, model_access, runner_host_id,
                                 runner_host_name, runner_host_kind, status, epoch, lease_expires_at, branch,
                                 created_at, started_at, trigger_id)
                VALUES (?, ?, ?, 'relay-test', ?, 'CLAUDE_CODE', 'FIRST', 1, ?, ?, 'BUDGET', ?, 'test-host', 'COMPANY',
                        'RUNNING', ?, ?, 'agent/relay-test', ?, ?, ?)
                ON CONFLICT (id) DO UPDATE
                   SET status = 'RUNNING', epoch = excluded.epoch, runner_host_id = excluded.runner_host_id,
                       lease_expires_at = excluded.lease_expires_at, finished_at = NULL""", statement -> {
            statement.setObject(1, runId);
            statement.setObject(2, companyId);
            statement.setObject(3, conversationId);
            statement.setObject(4, agentId);
            statement.setObject(5, actingPrincipalId);
            statement.setObject(6, payerPrincipalId);
            statement.setObject(7, runnerHostId);
            statement.setLong(8, epoch);
            statement.setObject(9, now.plusSeconds(3600).atOffset(ZoneOffset.UTC));
            statement.setObject(10, now.atOffset(ZoneOffset.UTC));
            statement.setObject(11, now.atOffset(ZoneOffset.UTC));
            statement.setObject(12, trigger);
        });
    }

    /** Whether the principal is a service identity. */
    private static boolean service(UUID companyId, UUID principalId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM principal WHERE id = ? AND kind = 'SERVICE'", principalId) > 0;
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * A turn of an existing conversation RUNNING on the host with the epoch, as its turn {@code turn}; the conversation
     * names its agent and person ({@link TestConversations#bare}).
     */
    public static void running(UUID companyId, UUID runId, long epoch, UUID runnerHostId, UUID conversationId,
            String conversationKey, UUID agentId, UUID personPrincipalId, int turn) {
        Instant now = Instant.now();
        execute(companyId, """
                INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                                 turn_number, acting_principal_id, payer_principal_id, model_access, runner_host_id,
                                 runner_host_name, runner_host_kind, status, epoch, lease_expires_at, branch,
                                 created_at, started_at)
                VALUES (?, ?, ?, ?, ?, 'CLAUDE_CODE', ?, ?, ?, ?, 'BUDGET', ?, 'test-host', 'COMPANY',
                        'RUNNING', ?, ?, ?, ?, ?)""", statement -> {
            statement.setObject(1, runId);
            statement.setObject(2, companyId);
            statement.setObject(3, conversationId);
            statement.setString(4, conversationKey);
            statement.setObject(5, agentId);
            statement.setString(6, turn == 1 ? "FIRST" : "MESSAGE");
            statement.setInt(7, turn);
            statement.setObject(8, personPrincipalId);
            statement.setObject(9, personPrincipalId);
            statement.setObject(10, runnerHostId);
            statement.setLong(11, epoch);
            statement.setObject(12, now.plusSeconds(3600).atOffset(ZoneOffset.UTC));
            statement.setString(13, "agent/" + conversationKey);
            statement.setObject(14, now.atOffset(ZoneOffset.UTC));
            statement.setObject(15, now.atOffset(ZoneOffset.UTC));
        });
    }

    /** The run ended with this status, a summary and no {@code finish} result. */
    public static void ended(UUID companyId, UUID runId, String status, String summary) {
        execute(companyId, """
                UPDATE run SET status = ?, summary = ?, finished_at = ?, lease_expires_at = NULL
                 WHERE id = ? AND status = 'RUNNING'""", statement -> {
            statement.setString(1, status);
            statement.setString(2, summary);
            statement.setObject(3, Instant.now().atOffset(ZoneOffset.UTC));
            statement.setObject(4, runId);
        });
    }

    /**
     * An agent of the company for runs written here (a run names its agent): a bare row, version 1, on Claude Code,
     * without an owner, systems or versions. Not for the agent module's own rules, which need an agent made through
     * its API.
     */
    public static UUID agent(UUID companyId) {
        UUID agentId = UUID.randomUUID();
        String suffix = agentId.toString().substring(0, 8);
        execute(companyId, """
                INSERT INTO agent (id, company_id, name, handle, runtimes, created_at, updated_at)
                VALUES (?, ?, ?, ?, '{CLAUDE_CODE}', ?, ?)""", statement -> {
            statement.setObject(1, agentId);
            statement.setObject(2, companyId);
            statement.setString(3, "Test agent " + suffix);
            statement.setString(4, "test-" + suffix);
            statement.setObject(5, Instant.now().atOffset(ZoneOffset.UTC));
            statement.setObject(6, Instant.now().atOffset(ZoneOffset.UTC));
        });
        return agentId;
    }

    /**
     * A turn of an existing conversation (its person acts and pays, by budget), RUNNING without a host (epoch 1, a
     * lease of an hour), or ended with {@code status} ({@code SUCCEEDED}, {@code FAILED}, {@code CANCELLED}): for the
     * conversation module's tests of what a run's requests raise.
     */
    public static void ofConversation(UUID companyId, UUID runId, UUID conversationId, String key, UUID agentId,
            UUID personPrincipalId, int turnNumber, String status) {
        Instant now = Instant.now();
        boolean running = "RUNNING".equals(status);
        execute(companyId, """
                INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                                 turn_number, acting_principal_id, payer_principal_id, model_access, status, epoch,
                                 lease_expires_at, branch, created_at, started_at, finished_at)
                VALUES (?, ?, ?, ?, ?, 'CLAUDE_CODE', ?, ?, ?, ?, 'BUDGET', ?, 1, ?, ?, ?, ?, ?)""", statement -> {
            statement.setObject(1, runId);
            statement.setObject(2, companyId);
            statement.setObject(3, conversationId);
            statement.setString(4, key);
            statement.setObject(5, agentId);
            statement.setString(6, turnNumber == 1 ? "FIRST" : "MESSAGE");
            statement.setInt(7, turnNumber);
            statement.setObject(8, personPrincipalId);
            statement.setObject(9, personPrincipalId);
            statement.setString(10, status);
            statement.setObject(11, running ? now.plusSeconds(3600).atOffset(ZoneOffset.UTC) : null);
            statement.setString(12, "agent/" + key);
            statement.setObject(13, now.atOffset(ZoneOffset.UTC));
            statement.setObject(14, now.atOffset(ZoneOffset.UTC));
            statement.setObject(15, running ? null : now.atOffset(ZoneOffset.UTC));
        });
    }

    /**
     * A run of a trigger's conversation ({@code TestTriggers.conversation}): acting as the trigger's author, paid by
     * its payer, as {@code run_conversation_ck} requires (V011), with the given status.
     */
    public static void ofTrigger(UUID companyId, UUID runId, UUID conversationId, String key, UUID agentId,
            UUID triggerId, UUID authorPrincipalId, UUID payerPrincipalId, String status) {
        Instant now = Instant.now();
        boolean running = "RUNNING".equals(status);
        execute(companyId, """
                INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                                 turn_number, acting_principal_id, payer_principal_id, model_access, status, epoch,
                                 lease_expires_at, branch, created_at, started_at, finished_at, trigger_id)
                VALUES (?, ?, ?, ?, ?, 'CLAUDE_CODE', 'FIRST', 1, ?, ?, 'BUDGET', ?, 1, ?, ?, ?, ?, ?, ?)""",
                statement -> {
                    statement.setObject(1, runId);
                    statement.setObject(2, companyId);
                    statement.setObject(3, conversationId);
                    statement.setString(4, key);
                    statement.setObject(5, agentId);
                    statement.setObject(6, authorPrincipalId);
                    statement.setObject(7, payerPrincipalId);
                    statement.setString(8, status);
                    statement.setObject(9, running ? now.plusSeconds(3600).atOffset(ZoneOffset.UTC) : null);
                    statement.setString(10, "agent/" + key);
                    statement.setObject(11, now.atOffset(ZoneOffset.UTC));
                    statement.setObject(12, now.atOffset(ZoneOffset.UTC));
                    statement.setObject(13, running ? null : now.atOffset(ZoneOffset.UTC));
                    statement.setObject(14, triggerId);
                });
    }

    /** The run ended: it is no longer running anywhere. */
    public static void ended(UUID companyId, UUID runId) {
        execute(companyId, """
                UPDATE run SET status = 'SUCCEEDED', finished_at = ?, lease_expires_at = NULL
                 WHERE id = ? AND status = 'RUNNING'""", statement -> {
            statement.setObject(1, Instant.now().atOffset(ZoneOffset.UTC));
            statement.setObject(2, runId);
        });
    }

    private interface Binder {

        void bind(PreparedStatement statement) throws SQLException;
    }

    private static void execute(UUID companyId, String sql, Binder binder) {
        try (Connection connection = TestDatabase.connectAsApp()) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("""
                    SELECT set_config('app.company_id', ?, true), set_config('app.user_id', '', true),
                           set_config('app.instance_admin', 'false', true)""")) {
                context.setString(1, companyId.toString());
                context.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                binder.bind(statement);
                statement.executeUpdate();
            }
            connection.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
