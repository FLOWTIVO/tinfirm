package com.flowtivo.tinfirm.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Conversation rows written directly, as the runtime role in the company's tenant context (TEST FIXTURE ONLY): a bare
 * conversation for the tests of the run module and of the model relay, which start or write runs of a conversation
 * without the conversation module (a run's conversation is a foreign key since V005). Each call commits.
 */
public final class TestConversations {

    private TestConversations() {
    }

    /**
     * A bare conversation, unless one with this id exists: a Tinfirm thread of the member's principal with the agent,
     * paid by the member's budget, without a subject, a workspace or a turn. Not for the conversation module's own
     * rules, which need a conversation started through its API.
     *
     * @param key a key of the conversation's company that no other conversation has
     */
    public static void bare(UUID companyId, UUID conversationId, String key, UUID agentId, UUID personPrincipalId,
            String runtime) {
        bare(companyId, conversationId, key, agentId, personPrincipalId, runtime, "APP");
    }

    /**
     * {@link #bare(UUID, UUID, String, UUID, UUID, String)} started elsewhere: {@code APP}, {@code CLIENT} or
     * {@code DIRECT_MESSAGE}. A conversation's origin is fixed (V011's {@code conversation_fixed_ck}); a trigger's
     * conversation is {@code TestTriggers.conversation}.
     */
    public static void bare(UUID companyId, UUID conversationId, String key, UUID agentId, UUID personPrincipalId,
            String runtime, String origin) {
        try (Connection connection = TestDatabase.connectAsApp()) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("""
                    SELECT set_config('app.company_id', ?, true), set_config('app.user_id', '', true),
                           set_config('app.instance_admin', 'false', true)""")) {
                context.setString(1, companyId.toString());
                context.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO conversation (id, company_id, agent_id, key, title, person_principal_id, runtime,
                                              payer_principal_id, model_access, origin, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'BUDGET', ?, ?, ?)
                    ON CONFLICT (id) DO NOTHING""")) {
                statement.setObject(1, conversationId);
                statement.setObject(2, companyId);
                statement.setObject(3, agentId);
                statement.setString(4, key);
                statement.setString(5, "Test conversation " + key);
                statement.setObject(6, personPrincipalId);
                statement.setString(7, runtime);
                statement.setObject(8, personPrincipalId);
                statement.setString(9, origin);
                statement.setObject(10, Instant.now().atOffset(ZoneOffset.UTC));
                statement.setObject(11, Instant.now().atOffset(ZoneOffset.UTC));
                statement.executeUpdate();
            }
            connection.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The bare conversation is about a subject: a system's issue, by its key, link and title. */
    public static void subject(UUID companyId, UUID conversationId, UUID systemId, String key, String url,
            String title) {
        execute(companyId, """
                UPDATE conversation SET subject_system_id = ?, subject_key = ?, subject_url = ?, subject_title = ?
                 WHERE id = ?""", statement -> {
            statement.setObject(1, systemId);
            statement.setString(2, key);
            statement.setString(3, url);
            statement.setString(4, title);
            statement.setObject(5, conversationId);
        });
    }

    /** The conversation ended, by its person. */
    public static void end(UUID companyId, UUID conversationId) {
        execute(companyId, """
                UPDATE conversation SET state = 'ENDED', ended_reason = 'USER', ended_at = now() WHERE id = ?""",
                statement -> statement.setObject(1, conversationId));
    }

    /**
     * A {@code BOUND} binding of the conversation on the system, as part 1 would store it: the person's own account
     * ({@code OWN}, without a grant) or a system's credential through a grant ({@code GRANTED}, without a holder;
     * ADR-0072). The account's identity is read from its row.
     *
     * @param holderPrincipalId the person, for their own account; null for a credential
     * @return the binding's id
     */
    public static UUID bind(UUID companyId, UUID conversationId, UUID systemId, UUID accountId,
            @org.jspecify.annotations.Nullable UUID holderPrincipalId, @org.jspecify.annotations.Nullable UUID grantId) {
        UUID bindingId = UUID.randomUUID();
        execute(companyId, """
                INSERT INTO conversation_binding (id, company_id, conversation_id, system_id, state, how, account_id,
                                                  holder_principal_id, external_id, grant_id, bound_at, created_at,
                                                  updated_at)
                SELECT ?, ?, ?, ?, 'BOUND', ?, a.id, a.principal_id, a.external_id, ?, now(), now(), now()
                  FROM account a WHERE a.id = ? AND a.principal_id IS NOT DISTINCT FROM ?""", statement -> {
            statement.setObject(1, bindingId);
            statement.setObject(2, companyId);
            statement.setObject(3, conversationId);
            statement.setObject(4, systemId);
            statement.setString(5, grantId == null ? "OWN" : "GRANTED");
            statement.setObject(6, grantId);
            statement.setObject(7, accountId);
            statement.setObject(8, holderPrincipalId);
        });
        return bindingId;
    }

    /** The binding is gone for good, its grant withdrawn. */
    public static void bindingGone(UUID companyId, UUID bindingId) {
        execute(companyId, """
                UPDATE conversation_binding SET state = 'GONE', reason = 'GRANT_REMOVED', updated_at = now()
                 WHERE id = ?""", statement -> statement.setObject(1, bindingId));
    }

    /**
     * An open question of the conversation, to its person (the package 3 contract, §3.12); null for a trigger's
     * conversation, whose questions name nobody (the package 4 contract, §5.8). Answers its id.
     */
    public static UUID question(UUID companyId, UUID conversationId, @Nullable UUID personPrincipalId,
            String question) {
        UUID id = UUID.randomUUID();
        execute(companyId, """
                INSERT INTO decision (id, company_id, conversation_id, kind, question, options, approver_principal_id,
                                      created_at)
                VALUES (?, ?, ?, 'QUESTION', ?, '[]'::jsonb, ?, now())""", statement -> {
            statement.setObject(1, id);
            statement.setObject(2, companyId);
            statement.setObject(3, conversationId);
            statement.setString(4, question);
            statement.setObject(5, personPrincipalId);
        });
        return id;
    }

    /**
     * An open approval of a network host for the conversation, to its approver (the package 3 contract, §3.12); answers
     * its id.
     */
    public static UUID hostApproval(UUID companyId, UUID conversationId, UUID approverPrincipalId, String host) {
        UUID id = UUID.randomUUID();
        execute(companyId, """
                INSERT INTO decision (id, company_id, conversation_id, kind, subject, reason, approver_principal_id,
                                      created_at)
                VALUES (?, ?, ?, 'APPROVAL', jsonb_build_object('type', 'HOST', 'host', ?::text), 'To read the docs',
                        ?, now())""", statement -> {
            statement.setObject(1, id);
            statement.setObject(2, companyId);
            statement.setObject(3, conversationId);
            statement.setString(4, host);
            statement.setObject(5, approverPrincipalId);
        });
        return id;
    }


    /** A member's principal of the company that has not left: the person of a bare conversation. */
    public static UUID anyMember(UUID companyId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return (UUID) sql.column("""
                    SELECT id FROM principal WHERE kind = 'MEMBER' AND status = 'ACTIVE' ORDER BY created_at, id
                     LIMIT 1""").getFirst();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
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
                if (statement.executeUpdate() != 1) {
                    throw new IllegalStateException("Not exactly one row: " + sql);
                }
            }
            connection.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
