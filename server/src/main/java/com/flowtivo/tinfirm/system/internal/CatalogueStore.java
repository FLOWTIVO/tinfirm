package com.flowtivo.tinfirm.system.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.system.RepositoryInfo;
import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.internal.http.Json;

import tools.jackson.databind.JsonNode;

/**
 * SQL access to {@code system_repository} and {@code system_tool} (V001), under row-level security: a code host's
 * repositories and an MCP server's catalogue (the package 1 contract, §3.4 and §3.5).
 */
@Repository
class CatalogueStore {

    private static final String REPOSITORY_COLUMNS = "id, company_id, system_id, url, name, default_branch";

    private static final String TOOL_COLUMNS = "name, title, description, input_schema, effect, hint_read_only,"
            + " hint_destructive, confirmed_at, enabled";

    private final JdbcClient jdbc;

    CatalogueStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- Repositories ----------------------------------------------------------------------------------------------

    void insertRepository(RepositoryInfo repository, Instant at, @Nullable UUID createdBy) {
        jdbc.sql("""
                INSERT INTO system_repository (id, company_id, system_id, url, name, default_branch, created_at,
                                               created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(repository.id(), repository.companyId(), repository.systemId(), repository.url(),
                    repository.name(), repository.defaultBranch(), SystemStore.ts(at), createdBy)
            .update();
    }

    boolean repositoryExists(UUID companyId, UUID systemId, String url) {
        return jdbc.sql("SELECT count(*) FROM system_repository WHERE company_id = ? AND system_id = ? AND url = ?")
            .params(companyId, systemId, url)
            .query(Long.class)
            .single() > 0;
    }

    /** The repositories of the system ordered by (name, id), after the given position. */
    List<RepositoryInfo> repositoryPage(UUID companyId, UUID systemId, @Nullable String afterName,
            @Nullable UUID afterId, int limit) {
        List<Object> params = new ArrayList<>(List.of(companyId, systemId));
        StringBuilder sql = new StringBuilder("SELECT " + REPOSITORY_COLUMNS
                + " FROM system_repository WHERE company_id = ? AND system_id = ?");
        if (afterName != null && afterId != null) {
            sql.append(" AND (name, id) > (?, ?)");
            params.add(afterName);
            params.add(afterId);
        }
        sql.append(" ORDER BY name, id LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(CatalogueStore::repository).list();
    }

    List<RepositoryInfo> repositories(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + REPOSITORY_COLUMNS
                + " FROM system_repository WHERE company_id = ? AND system_id = ? ORDER BY name, id")
            .params(companyId, systemId)
            .query(CatalogueStore::repository)
            .list();
    }

    Optional<RepositoryInfo> findRepository(UUID companyId, UUID systemId, UUID id) {
        return jdbc.sql("SELECT " + REPOSITORY_COLUMNS
                + " FROM system_repository WHERE company_id = ? AND system_id = ? AND id = ?")
            .params(companyId, systemId, id)
            .query(CatalogueStore::repository)
            .optional();
    }

    /** A repository of any system of the company. */
    Optional<RepositoryInfo> findRepository(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + REPOSITORY_COLUMNS + " FROM system_repository WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(CatalogueStore::repository)
            .optional();
    }

    void deleteRepository(UUID companyId, UUID id) {
        jdbc.sql("DELETE FROM system_repository WHERE company_id = ? AND id = ?").params(companyId, id).update();
    }

    private static RepositoryInfo repository(ResultSet rs, int row) throws SQLException {
        return new RepositoryInfo(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getString("url"), rs.getString("name"),
                rs.getString("default_branch"));
    }

    // ---- Tools -----------------------------------------------------------------------------------------------------

    /**
     * An MCP tool as stored.
     *
     * @param effect    the confirmed effect, {@code UNKNOWN} until an admin confirms one
     * @param confirmed whether an admin confirmed the effect
     */
    record ToolRow(String name, @Nullable String title, @Nullable String description, JsonNode inputSchema,
            ToolEffect effect, @Nullable Boolean hintReadOnly, @Nullable Boolean hintDestructive, boolean confirmed,
            boolean enabled) {
    }

    List<ToolRow> tools(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + TOOL_COLUMNS
                + " FROM system_tool WHERE company_id = ? AND system_id = ? ORDER BY name")
            .params(companyId, systemId)
            .query(CatalogueStore::tool)
            .list();
    }

    Optional<ToolRow> findTool(UUID companyId, UUID systemId, String name) {
        return jdbc.sql("SELECT " + TOOL_COLUMNS
                + " FROM system_tool WHERE company_id = ? AND system_id = ? AND name = ?")
            .params(companyId, systemId, name)
            .query(CatalogueStore::tool)
            .optional();
    }

    Optional<ToolRow> lockTool(UUID companyId, UUID systemId, String name) {
        return jdbc.sql("SELECT " + TOOL_COLUMNS
                + " FROM system_tool WHERE company_id = ? AND system_id = ? AND name = ? FOR UPDATE")
            .params(companyId, systemId, name)
            .query(CatalogueStore::tool)
            .optional();
    }

    void insertTool(UUID companyId, UUID systemId, ToolRow tool, Instant at) {
        jdbc.sql("""
                INSERT INTO system_tool (company_id, system_id, name, title, description, input_schema, effect,
                                         hint_read_only, hint_destructive, enabled, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?::jsonb, 'UNKNOWN', ?, ?, true, ?, ?)""")
            .params(companyId, systemId, tool.name(), tool.title(), tool.description(), Json.write(tool.inputSchema()),
                    tool.hintReadOnly(), tool.hintDestructive(), SystemStore.ts(at), SystemStore.ts(at))
            .update();
    }

    /**
     * Stores what the server lists now for a known tool; {@code unconfirm} drops the confirmed effect (its
     * description, schema or hints changed).
     */
    void updateTool(UUID companyId, UUID systemId, ToolRow tool, boolean unconfirm, Instant at) {
        jdbc.sql("""
                UPDATE system_tool SET title = ?, description = ?, input_schema = ?::jsonb, hint_read_only = ?,
                                       hint_destructive = ?, updated_at = ?,
                                       effect = CASE WHEN ? THEN 'UNKNOWN' ELSE effect END,
                                       confirmed_by = CASE WHEN ? THEN NULL ELSE confirmed_by END,
                                       confirmed_at = CASE WHEN ? THEN NULL ELSE confirmed_at END
                 WHERE company_id = ? AND system_id = ? AND name = ?""")
            .params(tool.title(), tool.description(), Json.write(tool.inputSchema()), tool.hintReadOnly(),
                    tool.hintDestructive(), SystemStore.ts(at), unconfirm, unconfirm, unconfirm, companyId, systemId,
                    tool.name())
            .update();
    }

    void deleteTool(UUID companyId, UUID systemId, String name) {
        jdbc.sql("DELETE FROM system_tool WHERE company_id = ? AND system_id = ? AND name = ?")
            .params(companyId, systemId, name)
            .update();
    }

    void confirmTool(UUID companyId, UUID systemId, String name, ToolEffect effect, @Nullable UUID by, Instant at) {
        jdbc.sql("""
                UPDATE system_tool SET effect = ?, confirmed_by = ?, confirmed_at = ?, updated_at = ?
                 WHERE company_id = ? AND system_id = ? AND name = ?""")
            .params(effect.name(), by, SystemStore.ts(at), SystemStore.ts(at), companyId, systemId, name)
            .update();
    }

    void switchTool(UUID companyId, UUID systemId, String name, boolean enabled, Instant at) {
        jdbc.sql("UPDATE system_tool SET enabled = ?, updated_at = ? WHERE company_id = ? AND system_id = ? AND name = ?")
            .params(enabled, SystemStore.ts(at), companyId, systemId, name)
            .update();
    }

    private static ToolRow tool(ResultSet rs, int row) throws SQLException {
        JsonNode schema = Json.parse(rs.getString("input_schema"));
        return new ToolRow(rs.getString("name"), rs.getString("title"), rs.getString("description"),
                schema != null ? schema : Json.tree(java.util.Map.of("type", "object")),
                ToolEffect.valueOf(rs.getString("effect")), (Boolean) rs.getObject("hint_read_only"),
                (Boolean) rs.getObject("hint_destructive"), rs.getObject("confirmed_at", OffsetDateTime.class) != null,
                rs.getBoolean("enabled"));
    }
}
