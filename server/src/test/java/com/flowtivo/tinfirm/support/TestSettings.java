package com.flowtivo.tinfirm.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * A person's settings of an agent written directly, as the runtime role in the company's tenant context (TEST FIXTURE
 * ONLY): a run paid by a budget names its model ({@code MODEL_REQUIRED}), so a person whose conversations a test runs
 * has settings that name one. Written without the settings' API, which would audit a change. Each call commits.
 */
public final class TestSettings {

    /** The model the fixtures' settings name: one of the bundled gateway's default models. */
    public static final String MODEL = "claude-sonnet-4-5";

    private TestSettings() {
    }

    /**
     * {@link #MODEL} in the person's settings of the agent, unless they name a model: the settings are made when the
     * person has none, as their first use would make them (the defaults, with the agent's current version accepted).
     */
    public static void nameModel(UUID companyId, UUID agentId, UUID principalId) {
        try (Connection connection = TestDatabase.connectAsApp()) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("""
                    SELECT set_config('app.company_id', ?, true), set_config('app.user_id', '', true),
                           set_config('app.instance_admin', 'false', true)""")) {
                context.setString(1, companyId.toString());
                context.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO agent_settings (id, company_id, agent_id, principal_id, model, accepted_version,
                                                created_at, updated_at)
                    SELECT ?, company_id, id, ?, ?, version, now(), now() FROM agent WHERE id = ?
                    ON CONFLICT (agent_id, principal_id) DO UPDATE
                       SET model = coalesce(agent_settings.model, excluded.model)""")) {
                statement.setObject(1, UUID.randomUUID());
                statement.setObject(2, principalId);
                statement.setString(3, MODEL);
                statement.setObject(4, agentId);
                statement.executeUpdate();
            }
            connection.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
