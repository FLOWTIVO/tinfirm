package com.flowtivo.tinfirm.agent.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.runner.EffortLevel;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.sql.Constraints;
import com.flowtivo.tinfirm.system.ToolEffect;

/**
 * SQL access to {@code agent_settings}, {@code agent_settings_system} and {@code agent_settings_tool} (V003), under
 * row-level security: one person's settings of one agent (the package 3 contract, §3.7 and §11), or, from package 4,
 * a trigger's (V011, {@code trigger_id}: the package 4 contract, §3.3).
 */
@Repository
class SettingsStore {

    private static final String COLUMNS = "id, company_id, agent_id, principal_id, trigger_id, runtime, model, effort,"
            + " model_access, subscription_host_id, subscription_label, instructions, default_repository_ids,"
            + " default_projects::text AS default_projects, accepted_version, memory, auto_retry, created_at,"
            + " updated_at";

    /** The check V003's triggers raise for a system, tool or own server that would widen the agent (I4). */
    static final String WIDEN_CHECK = "agent_settings_widen_ck";

    private final JdbcClient jdbc;

    SettingsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The two model accesses settings name. */
    enum ModelAccessKind {
        BUDGET, SUBSCRIPTION
    }

    /**
     * A settings row; {@code defaultProjects} is the stored JSON array. Exactly one owner: a person's principal, or a
     * trigger. {@code memoryOn}: the owner turned the agent's memory on for themselves (ADR-0063, opt-in);
     * {@code autoRetry}: the owner chose automatic retries of failed turns (ADR-0073, opt-in).
     */
    record SettingsRow(UUID id, UUID companyId, UUID agentId, @Nullable UUID principalId, @Nullable UUID triggerId,
            @Nullable RuntimeKind runtime,
            @Nullable String model, EffortLevel effort, ModelAccessKind modelAccess, @Nullable UUID subscriptionHostId,
            @Nullable String subscriptionLabel, @Nullable String instructions, List<UUID> defaultRepositoryIds,
            String defaultProjects, int acceptedVersion, boolean memoryOn, boolean autoRetry, Instant createdAt,
            Instant updatedAt) {

        SettingsRow {
            defaultRepositoryIds = List.copyOf(defaultRepositoryIds);
        }

        /** The defaults, with the version accepted. */
        static SettingsRow defaults(UUID id, UUID companyId, UUID agentId, UUID principalId, int acceptedVersion,
                Instant at) {
            return new SettingsRow(id, companyId, agentId, principalId, null, null, null, EffortLevel.DEFAULT,
                    ModelAccessKind.BUDGET, null, null, null, List.of(), "[]", acceptedVersion, false, false, at, at);
        }

        /** A trigger's defaults, with the version its author accepted. */
        static SettingsRow triggerDefaults(UUID id, UUID companyId, UUID agentId, UUID triggerId, int acceptedVersion,
                Instant at) {
            return new SettingsRow(id, companyId, agentId, null, triggerId, null, null, EffortLevel.DEFAULT,
                    ModelAccessKind.BUDGET, null, null, null, List.of(), "[]", acceptedVersion, false, false, at, at);
        }
    }

    /** A system switched off ({@code OFF}) or the person's own server added ({@code ADDED}). */
    record SystemRow(UUID systemId, String state) {

        static final String OFF = "OFF";

        static final String ADDED = "ADDED";
    }

    /** A tool's mode in the settings: {@code ASK} or {@code OFF} on the agent's systems, else an own server's. */
    record ToolRow(UUID systemId, String tool, String mode, ToolEffect chosenForEffect) {
    }

    Optional<SettingsRow> find(UUID companyId, UUID agentId, UUID principalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_settings WHERE company_id = ? AND agent_id = ?"
                + " AND principal_id = ?")
            .params(companyId, agentId, principalId)
            .query(SettingsStore::row)
            .optional();
    }

    Optional<SettingsRow> lock(UUID companyId, UUID agentId, UUID principalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_settings WHERE company_id = ? AND agent_id = ?"
                + " AND principal_id = ? FOR UPDATE")
            .params(companyId, agentId, principalId)
            .query(SettingsStore::row)
            .optional();
    }

    Optional<SettingsRow> findOfTrigger(UUID companyId, UUID triggerId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_settings WHERE company_id = ? AND trigger_id = ?")
            .params(companyId, triggerId)
            .query(SettingsStore::row)
            .optional();
    }

    Optional<SettingsRow> lockOfTrigger(UUID companyId, UUID triggerId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_settings WHERE company_id = ? AND trigger_id = ?"
                + " FOR UPDATE")
            .params(companyId, triggerId)
            .query(SettingsStore::row)
            .optional();
    }

    /** A trigger's row, made with the trigger (V011: unique per trigger, its agent the trigger's). */
    void insertOfTrigger(SettingsRow row) {
        jdbc.sql("""
                INSERT INTO agent_settings (id, company_id, agent_id, trigger_id, effort, model_access,
                                            accepted_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(row.id(), row.companyId(), row.agentId(), row.triggerId(), row.effort().name(),
                    row.modelAccess().name(), row.acceptedVersion(), ts(row.createdAt()), ts(row.updatedAt()))
            .update();
    }

    /** Inserts the row unless the person has one for the agent (I5): first use is concurrent-safe. */
    void insertIfAbsent(SettingsRow row) {
        jdbc.sql("""
                INSERT INTO agent_settings (id, company_id, agent_id, principal_id, effort, model_access,
                                            accepted_version, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (agent_id, principal_id) DO NOTHING""")
            .params(row.id(), row.companyId(), row.agentId(), row.principalId(), row.effort().name(),
                    row.modelAccess().name(), row.acceptedVersion(), ts(row.createdAt()), ts(row.updatedAt()))
            .update();
    }

    /** Writes every field of the row but its identity and its accepted version. */
    void update(SettingsRow row) {
        jdbc.sql("""
                UPDATE agent_settings
                   SET runtime = ?, model = ?, effort = ?, model_access = ?, subscription_host_id = ?,
                       subscription_label = ?, instructions = ?, default_repository_ids = CAST(? AS uuid[]),
                       default_projects = CAST(? AS jsonb), memory = ?, auto_retry = ?, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(row.runtime() != null ? row.runtime().name() : null, row.model(), row.effort().name(),
                    row.modelAccess().name(), row.subscriptionHostId(), row.subscriptionLabel(), row.instructions(),
                    row.defaultRepositoryIds().stream().map(UUID::toString).toArray(String[]::new),
                    row.defaultProjects(), row.memoryOn(), row.autoRetry(), ts(row.updatedAt()), row.companyId(),
                    row.id())
            .update();
    }

    void setAcceptedVersion(UUID companyId, UUID settingsId, int version, Instant at) {
        jdbc.sql("UPDATE agent_settings SET accepted_version = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(version, ts(at), companyId, settingsId)
            .update();
    }

    /**
     * The person's settings follow a version they made themselves (an editor's change) when they were up to date:
     * from {@code previous} to {@code next}. Answers whether a row moved.
     */
    boolean advanceAccepted(UUID companyId, UUID agentId, UUID principalId, int previous, int next) {
        return jdbc.sql("""
                UPDATE agent_settings SET accepted_version = ?
                 WHERE company_id = ? AND agent_id = ? AND principal_id = ? AND accepted_version = ?""")
            .params(next, companyId, agentId, principalId, previous)
            .update() > 0;
    }

    /** The versions the principal accepted, by agent; an agent without settings is absent. */
    Map<UUID, Integer> acceptedVersions(UUID companyId, UUID principalId, Collection<UUID> agentIds) {
        Map<UUID, Integer> versions = new HashMap<>();
        if (agentIds.isEmpty()) {
            return versions;
        }
        jdbc.sql("""
                SELECT agent_id, accepted_version FROM agent_settings
                 WHERE company_id = :companyId AND principal_id = :principalId AND agent_id IN (:ids)""")
            .param("companyId", companyId)
            .param("principalId", principalId)
            .param("ids", Set.copyOf(agentIds))
            .query(rs -> {
                versions.put(rs.getObject("agent_id", UUID.class), rs.getInt("accepted_version"));
            });
        return versions;
    }

    List<SystemRow> systems(UUID companyId, UUID settingsId) {
        return jdbc.sql("""
                SELECT system_id, state FROM agent_settings_system
                 WHERE company_id = ? AND settings_id = ? ORDER BY system_id""")
            .params(companyId, settingsId)
            .query((rs, n) -> new SystemRow(rs.getObject("system_id", UUID.class), rs.getString("state")))
            .list();
    }

    List<ToolRow> tools(UUID companyId, UUID settingsId) {
        return jdbc.sql("""
                SELECT system_id, tool, mode, chosen_for_effect FROM agent_settings_tool
                 WHERE company_id = ? AND settings_id = ? ORDER BY system_id, tool""")
            .params(companyId, settingsId)
            .query((rs, n) -> new ToolRow(rs.getObject("system_id", UUID.class), rs.getString("tool"),
                    rs.getString("mode"), ToolEffect.valueOf(rs.getString("chosen_for_effect"))))
            .list();
    }

    /**
     * Whether the database refused a row of the settings because it would widen the agent (I4): V003's triggers raise
     * {@value #WIDEN_CHECK}, which only the server's error names, not its message.
     */
    static boolean widens(DataAccessException ex) {
        return Constraints.violated(ex, WIDEN_CHECK);
    }

    /**
     * Replaces the settings' systems and tools as a whole; the database checks I4 on each row ({@link #widens} tells
     * its refusal).
     */
    void replaceSystemsAndTools(UUID companyId, UUID settingsId, List<SystemRow> systems, List<ToolRow> tools) {
        jdbc.sql("DELETE FROM agent_settings_tool WHERE company_id = ? AND settings_id = ?")
            .params(companyId, settingsId)
            .update();
        jdbc.sql("DELETE FROM agent_settings_system WHERE company_id = ? AND settings_id = ?")
            .params(companyId, settingsId)
            .update();
        for (SystemRow system : systems) {
            jdbc.sql("""
                    INSERT INTO agent_settings_system (company_id, settings_id, system_id, state)
                    VALUES (?, ?, ?, ?)""")
                .params(companyId, settingsId, system.systemId(), system.state())
                .update();
        }
        for (ToolRow tool : tools) {
            jdbc.sql("""
                    INSERT INTO agent_settings_tool (company_id, settings_id, system_id, tool, mode,
                                                     chosen_for_effect)
                    VALUES (?, ?, ?, ?, ?, ?)""")
                .params(companyId, settingsId, tool.systemId(), tool.tool(), tool.mode(),
                        tool.chosenForEffect().name())
                .update();
        }
    }

    /**
     * Systems left the agent: every person's switches of them go (a system switched off, a tool's {@code ASK} or
     * {@code OFF}), since they narrowed nothing any longer.
     */
    void forgetSystems(UUID companyId, UUID agentId, Collection<UUID> systemIds) {
        if (systemIds.isEmpty()) {
            return;
        }
        Map<String, Object> params = Map.of("companyId", companyId, "agentId", agentId, "systemIds",
                Set.copyOf(systemIds));
        jdbc.sql("""
                DELETE FROM agent_settings_tool t
                 USING agent_settings s
                 WHERE t.settings_id = s.id AND s.company_id = :companyId AND s.agent_id = :agentId
                   AND t.system_id IN (:systemIds)
                   AND NOT EXISTS (SELECT 1 FROM agent_settings_system x
                                    WHERE x.settings_id = t.settings_id AND x.system_id = t.system_id
                                      AND x.state = 'ADDED')""")
            .params(params)
            .update();
        jdbc.sql("""
                DELETE FROM agent_settings_system x
                 USING agent_settings s
                 WHERE x.settings_id = s.id AND s.company_id = :companyId AND s.agent_id = :agentId
                   AND x.system_id IN (:systemIds) AND x.state = 'OFF'""")
            .params(params)
            .update();
    }

    /** The principal's settings, all agents: a leaving member's go. Answers the agents they were of. */
    List<UUID> deleteOfPrincipal(UUID companyId, UUID principalId) {
        return jdbc.sql("DELETE FROM agent_settings WHERE company_id = ? AND principal_id = ? RETURNING agent_id")
            .params(companyId, principalId)
            .query(UUID.class)
            .list();
    }

    /** Every person's settings of the agent: a deleted agent's go. */
    int deleteOfAgent(UUID companyId, UUID agentId) {
        return jdbc.sql("DELETE FROM agent_settings WHERE company_id = ? AND agent_id = ?")
            .params(companyId, agentId)
            .update();
    }

    private static SettingsRow row(ResultSet rs, int rowNum) throws SQLException {
        String runtime = rs.getString("runtime");
        return new SettingsRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("agent_id", UUID.class), rs.getObject("principal_id", UUID.class),
                rs.getObject("trigger_id", UUID.class), runtime != null ? RuntimeKind.valueOf(runtime) : null,
                rs.getString("model"),
                EffortLevel.valueOf(rs.getString("effort")), ModelAccessKind.valueOf(rs.getString("model_access")),
                rs.getObject("subscription_host_id", UUID.class), rs.getString("subscription_label"),
                rs.getString("instructions"), uuids(rs.getArray("default_repository_ids")),
                rs.getString("default_projects"), rs.getInt("accepted_version"), rs.getBoolean("memory"),
                rs.getBoolean("auto_retry"), instant(rs, "created_at"), instant(rs, "updated_at"));
    }

    private static List<UUID> uuids(Array array) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        for (Object id : (Object[]) array.getArray()) {
            ids.add(id instanceof UUID uuid ? uuid : UUID.fromString(id.toString()));
        }
        return ids;
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
