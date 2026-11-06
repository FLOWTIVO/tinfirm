package com.flowtivo.tinfirm.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Trigger rows written directly, as the runtime role in the company's tenant context (TEST FIXTURE ONLY): a bare
 * schedule of an agent with its settings, and a conversation of it, for tests that need a trigger's conversation or
 * run without the trigger module's API (a run paid by a service identity is a trigger's, V011). Each call commits.
 */
public final class TestTriggers {

    private TestTriggers() {
    }

    /**
     * A trigger's create body with {@link TestSettings#MODEL} in its settings, unless they name a model: a run paid
     * by a budget names its model, so a trigger without one is refused ({@code 400 MODEL_REQUIRED}).
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> withModel(Map<String, Object> body) {
        Map<String, Object> copy = new LinkedHashMap<>(body);
        Map<String, Object> settings = body.get("settings") instanceof Map<?, ?> given
                ? new LinkedHashMap<>((Map<String, Object>) given) : new LinkedHashMap<>();
        settings.putIfAbsent("model", TestSettings.MODEL);
        copy.put("settings", settings);
        return copy;
    }

    /**
     * A bare schedule ({@code 0 9 * * *} in UTC, overlap {@code SKIP}, catch-up {@code NONE}) of the agent, authored by
     * the member's principal and paid by the payer (the author or a service identity), {@code ACTIVE}, with its
     * settings (the defaults with {@link TestSettings#MODEL}, version 1 accepted).
     */
    public static UUID schedule(UUID companyId, UUID agentId, UUID authorPrincipalId, UUID payerPrincipalId) {
        UUID triggerId = UUID.randomUUID();
        Instant now = Instant.now();
        try (Connection connection = TestDatabase.connectAsApp()) {
            connection.setAutoCommit(false);
            context(connection, companyId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO trigger (id, company_id, name, source, schedule_cron, schedule_zone, overlap, catch_up,
                                         agent_id, instruction, author_principal_id, payer_principal_id, armed_at,
                                         created_at, updated_at)
                    VALUES (?, ?, ?, 'SCHEDULE', '0 9 * * *', 'UTC', 'SKIP', 'NONE', ?, 'Report on yesterday.', ?, ?,
                            ?, ?, ?)""")) {
                statement.setObject(1, triggerId);
                statement.setObject(2, companyId);
                statement.setString(3, "Test schedule " + triggerId.toString().substring(0, 8));
                statement.setObject(4, agentId);
                statement.setObject(5, authorPrincipalId);
                statement.setObject(6, payerPrincipalId);
                statement.setObject(7, now.atOffset(ZoneOffset.UTC));
                statement.setObject(8, now.atOffset(ZoneOffset.UTC));
                statement.setObject(9, now.atOffset(ZoneOffset.UTC));
                statement.executeUpdate();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_settings (id, company_id, agent_id, trigger_id, model, accepted_version,
                                                created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 1, ?, ?)""")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, companyId);
                statement.setObject(3, agentId);
                statement.setObject(4, triggerId);
                statement.setString(5, TestSettings.MODEL);
                statement.setObject(6, now.atOffset(ZoneOffset.UTC));
                statement.setObject(7, now.atOffset(ZoneOffset.UTC));
                statement.executeUpdate();
            }
            connection.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return triggerId;
    }

    /**
     * A bare conversation of the schedule, unless one with this id exists: its {@code SCHEDULE} place, the trigger's
     * agent, author and payer, by budget, without a subject, a workspace, a binding or a turn.
     *
     * @param key a key of the company that no other conversation has
     */
    public static void conversation(UUID companyId, UUID conversationId, String key, UUID triggerId, UUID agentId,
            UUID authorPrincipalId, UUID payerPrincipalId) {
        try (Connection connection = TestDatabase.connectAsApp()) {
            connection.setAutoCommit(false);
            context(connection, companyId);
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO conversation (id, company_id, agent_id, key, title, place, place_ref, origin,
                                              trigger_id, author_principal_id, runtime, payer_principal_id,
                                              model_access, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'SCHEDULE', jsonb_build_object('triggerId', ?::text), 'TRIGGER', ?, ?,
                            'CLAUDE_CODE', ?, 'BUDGET', ?, ?)
                    ON CONFLICT (id) DO NOTHING""")) {
                statement.setObject(1, conversationId);
                statement.setObject(2, companyId);
                statement.setObject(3, agentId);
                statement.setString(4, key);
                statement.setString(5, "Test schedule conversation " + key);
                statement.setString(6, triggerId.toString());
                statement.setObject(7, triggerId);
                statement.setObject(8, authorPrincipalId);
                statement.setObject(9, payerPrincipalId);
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

    private static void context(Connection connection, UUID companyId) throws SQLException {
        try (PreparedStatement context = connection.prepareStatement("""
                SELECT set_config('app.company_id', ?, true), set_config('app.user_id', '', true),
                       set_config('app.instance_admin', 'false', true)""")) {
            context.setString(1, companyId.toString());
            context.execute();
        }
    }
}
