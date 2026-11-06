package com.flowtivo.tinfirm.system.internal;

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
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.internal.http.Json;

import tools.jackson.databind.JsonNode;

/**
 * SQL access to {@code external_system} and {@code system_setting} (V001), under row-level security: the systems of
 * the package 1 contract, §3.3, and the company's switch of personal MCP servers (§10).
 */
@Repository
class SystemStore {

    private static final String COLUMNS = "id, company_id, name, handle, kind, base_url, owner_principal_id,"
            + " oauth_client_id, oauth_client_secret_id, oauth_scopes, oauth_metadata, settings, status, last_error,"
            + " created_at, updated_at, paused_at, last_tested_at, last_test_ok, last_test_error,"
            + " last_test_with_account, last_tested_by_principal_id, last_test_account_id, accounts_required";

    private final JdbcClient jdbc;

    SystemStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A system's last test (V043): when it ended, its outcome, whether it reached the system with an account, who ran
     * it (their principal; null for an instance admin who is not a member, a principal gone or a person erased) and the
     * tester's own account it used (null without one, or once it is gone).
     */
    record LastTest(Instant at, boolean ok, @Nullable String error, boolean withAccount,
            @Nullable UUID byPrincipalId, @Nullable UUID accountId) {
    }

    /**
     * A system row; {@code settings} is a JSON object, {@code oauthMetadata} an MCP server's discovered app,
     * {@code lastTest} null until it is tested, {@code accountsRequired} false only for an MCP server its admin set to
     * need no account (V048).
     */
    record SystemRow(UUID id, UUID companyId, String name, String handle, SystemKind kind, @Nullable String baseUrl,
            @Nullable UUID ownerPrincipalId, @Nullable String oauthClientId, @Nullable UUID oauthClientSecretId,
            @Nullable String oauthScopes, @Nullable JsonNode oauthMetadata, JsonNode settings, String status,
            @Nullable String lastError, Instant createdAt, Instant updatedAt, @Nullable Instant pausedAt,
            @Nullable LastTest lastTest, boolean accountsRequired) {

        boolean paused() {
            return pausedAt != null;
        }

        boolean personal() {
            return ownerPrincipalId != null;
        }

        @Nullable String authHeader() {
            return Json.text(settings, "authHeader");
        }

        SystemInfo info() {
            return new SystemInfo(id, companyId, name, handle, kind, baseUrl, ownerPrincipalId, authHeader(),
                    oauthClientId != null, pausedAt != null, accountsRequired);
        }
    }

    void insert(SystemRow row, @Nullable UUID createdBy) {
        jdbc.sql("""
                INSERT INTO external_system (id, company_id, name, handle, kind, base_url, owner_principal_id,
                                             settings, created_at, updated_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?)""")
            .params(row.id(), row.companyId(), row.name(), row.handle(), row.kind().name(), row.baseUrl(),
                    row.ownerPrincipalId(), Json.write(row.settings()), ts(row.createdAt()), ts(row.updatedAt()),
                    createdBy)
            .update();
    }

    Optional<SystemRow> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_system WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(SystemStore::row)
            .optional();
    }

    /** The system whose app keeps its client secret in the secret. */
    Optional<SystemRow> withClientSecret(UUID companyId, UUID secretId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_system WHERE company_id = ?"
                + " AND oauth_client_secret_id = ? ORDER BY id LIMIT 1")
            .params(companyId, secretId)
            .query(SystemStore::row)
            .optional();
    }

    Optional<SystemRow> lock(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_system WHERE company_id = ? AND id = ? FOR UPDATE")
            .params(companyId, id)
            .query(SystemStore::row)
            .optional();
    }

    /**
     * The system, its row locked {@code FOR SHARE}: against a move of an MCP server ({@link #move}, under
     * {@link #lock}'s {@code FOR UPDATE}) and any other change of the row until the caller's transaction ends, not
     * against another shared lock. For a module that stores a credential for the system as the row stands (ADR-0079).
     */
    Optional<SystemRow> lockShared(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_system WHERE company_id = ? AND id = ? FOR SHARE")
            .params(companyId, id)
            .query(SystemStore::row)
            .optional();
    }

    Map<UUID, SystemRow> findAll(UUID companyId, Collection<UUID> ids) {
        Map<UUID, SystemRow> rows = new HashMap<>();
        if (ids.isEmpty()) {
            return rows;
        }
        jdbc.sql("SELECT " + COLUMNS + " FROM external_system WHERE company_id = :companyId AND id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(ids))
            .query(SystemStore::row)
            .list()
            .forEach(row -> rows.put(row.id(), row));
        return rows;
    }

    /**
     * The company's systems and the personal servers of the principal (none when it is null), ordered by (name, id),
     * after the given position.
     */
    List<SystemRow> page(UUID companyId, @Nullable UUID ownerPrincipalId, @Nullable String afterName,
            @Nullable UUID afterId, int limit) {
        List<Object> params = new ArrayList<>(List.of(companyId));
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM external_system WHERE company_id = ?");
        if (ownerPrincipalId != null) {
            sql.append(" AND (owner_principal_id IS NULL OR owner_principal_id = ?)");
            params.add(ownerPrincipalId);
        }
        else {
            sql.append(" AND owner_principal_id IS NULL");
        }
        if (afterName != null && afterId != null) {
            sql.append(" AND (name, id) > (?, ?)");
            params.add(afterName);
            params.add(afterId);
        }
        sql.append(" ORDER BY name, id LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(SystemStore::row).list();
    }

    /** The personal servers of the principal, locked, for its member's leaving. */
    List<SystemRow> lockPersonal(UUID companyId, UUID ownerPrincipalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM external_system WHERE company_id = ? AND owner_principal_id = ?"
                + " ORDER BY id FOR UPDATE")
            .params(companyId, ownerPrincipalId)
            .query(SystemStore::row)
            .list();
    }

    boolean nameTaken(UUID companyId, String name, @Nullable UUID exceptId) {
        return jdbc.sql("""
                SELECT count(*) FROM external_system
                 WHERE company_id = ? AND lower(name) = lower(?) AND id IS DISTINCT FROM ?""")
            .params(companyId, name, exceptId)
            .query(Long.class)
            .single() > 0;
    }

    boolean handleTaken(UUID companyId, String handle, @Nullable UUID exceptId) {
        return jdbc.sql("""
                SELECT count(*) FROM external_system WHERE company_id = ? AND handle = ? AND id IS DISTINCT FROM ?""")
            .params(companyId, handle, exceptId)
            .query(Long.class)
            .single() > 0;
    }

    /**
     * Takes the company's handles until the transaction ends ({@code system_handle:{companyId}}, a transaction-scoped
     * advisory lock): a registration takes it before any row and before it picks a free handle, so that two
     * registrations never pick the same one (CONVENTIONS §10, the lock order).
     */
    void lockHandles(UUID companyId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
            .params("system_handle:" + companyId)
            .query((rs, row) -> 1)
            .list();
    }

    /** Renames the system or changes its settings; its handle never changes. */
    void update(UUID companyId, UUID id, String name, JsonNode settings, Instant at) {
        jdbc.sql("""
                UPDATE external_system SET name = ?, settings = ?::jsonb, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(name, Json.write(settings), ts(at), companyId, id)
            .update();
    }

    /** Whether an MCP server needs accounts (V048: the database refuses false for any other kind). */
    void updateAccountsRequired(UUID companyId, UUID id, boolean accountsRequired, Instant at) {
        jdbc.sql("UPDATE external_system SET accounts_required = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(accountsRequired, ts(at), companyId, id)
            .update();
    }

    void updateApp(UUID companyId, UUID id, String clientId, @Nullable UUID clientSecretId, @Nullable String scopes,
            @Nullable JsonNode metadata, Instant at) {
        jdbc.sql("""
                UPDATE external_system SET oauth_client_id = ?, oauth_client_secret_id = ?, oauth_scopes = ?,
                                           oauth_metadata = ?::jsonb, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(clientId, clientSecretId, scopes, metadata != null ? Json.write(metadata) : null, ts(at),
                    companyId, id)
            .update();
    }

    /** Removes the app: the client, its secret's id, the scopes and the endpoints. */
    void clearApp(UUID companyId, UUID id, Instant at) {
        jdbc.sql("""
                UPDATE external_system SET oauth_client_id = NULL, oauth_client_secret_id = NULL, oauth_scopes = NULL,
                                           oauth_metadata = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(ts(at), companyId, id)
            .update();
    }

    /**
     * An MCP server's new endpoint (ADR-0079), in one statement: the app found at the old address goes (its secret's
     * deletion is the caller's), and the system is {@code NEW} again, without its last test, which was of the old one.
     */
    void move(UUID companyId, UUID id, String baseUrl, Instant at) {
        jdbc.sql("""
                UPDATE external_system
                   SET base_url = ?, oauth_client_id = NULL, oauth_client_secret_id = NULL, oauth_scopes = NULL,
                       oauth_metadata = NULL, status = 'NEW', last_error = NULL, last_tested_at = NULL,
                       last_test_ok = NULL, last_test_error = NULL, last_test_with_account = NULL,
                       last_tested_by_principal_id = NULL, last_test_account_id = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(baseUrl, ts(at), companyId, id)
            .update();
    }

    void updateStatus(UUID companyId, UUID id, String status, @Nullable String lastError) {
        jdbc.sql("UPDATE external_system SET status = ?, last_error = ? WHERE company_id = ? AND id = ?")
            .params(status, lastError, companyId, id)
            .update();
    }

    /**
     * A test's outcome: the system's status and last error, and its last test (V043), replacing the one before, in one
     * statement.
     */
    void recordTest(UUID companyId, UUID id, LastTest test) {
        jdbc.sql("""
                UPDATE external_system
                   SET status = ?, last_error = ?, last_tested_at = ?, last_test_ok = ?, last_test_error = ?,
                       last_test_with_account = ?, last_tested_by_principal_id = ?, last_test_account_id = ?
                 WHERE company_id = ? AND id = ?""")
            .params(test.ok() ? "OK" : "ERROR", test.error(), ts(test.at()), test.ok(), test.error(),
                    test.withAccount(), test.byPrincipalId(), test.accountId(), companyId, id)
            .update();
    }

    /**
     * Forgets the principal as the tester of every system's last test, for a person's erasure: the time and the
     * outcome stay. Answers how many tests named them.
     */
    int forgetTester(UUID companyId, UUID principalId) {
        return jdbc.sql("""
                UPDATE external_system SET last_tested_by_principal_id = NULL, last_test_account_id = NULL
                 WHERE company_id = ? AND last_tested_by_principal_id = ?""")
            .params(companyId, principalId)
            .update();
    }

    /** How many systems' last tests name the principal: what {@link #forgetTester} would forget. */
    int countTester(UUID companyId, UUID principalId) {
        return jdbc.sql("""
                SELECT count(*) FROM external_system WHERE company_id = ? AND last_tested_by_principal_id = ?""")
            .params(companyId, principalId)
            .query(Integer.class)
            .single();
    }

    /** Deletes the system; its repositories and tools go with it (ON DELETE CASCADE). */
    void delete(UUID companyId, UUID id) {
        jdbc.sql("DELETE FROM external_system WHERE company_id = ? AND id = ?").params(companyId, id).update();
    }

    // ---- The company's setting ------------------------------------------------------------------------------------

    /** Whether personal MCP servers are on: the stored switch, on when none was stored. */
    boolean personalSystemsOn(UUID companyId) {
        return jdbc.sql("SELECT personal_mcp_servers FROM system_setting WHERE company_id = ?")
            .param(companyId)
            .query(Boolean.class)
            .optional()
            .orElse(true);
    }

    void setPersonalSystems(UUID companyId, boolean on, Instant at, @Nullable UUID by) {
        jdbc.sql("""
                INSERT INTO system_setting (company_id, personal_mcp_servers, updated_at, updated_by)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (company_id) DO UPDATE
                   SET personal_mcp_servers = excluded.personal_mcp_servers, updated_at = excluded.updated_at,
                       updated_by = excluded.updated_by""")
            .params(companyId, on, ts(at), by)
            .update();
    }

    private static SystemRow row(ResultSet rs, int row) throws SQLException {
        String metadata = rs.getString("oauth_metadata");
        JsonNode settings = Json.parse(rs.getString("settings"));
        return new SystemRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name"), rs.getString("handle"), SystemKind.valueOf(rs.getString("kind")),
                rs.getString("base_url"), rs.getObject("owner_principal_id", UUID.class),
                rs.getString("oauth_client_id"), rs.getObject("oauth_client_secret_id", UUID.class),
                rs.getString("oauth_scopes"), metadata != null ? Json.parse(metadata) : null,
                settings != null ? settings : Json.tree(Map.of()), rs.getString("status"), rs.getString("last_error"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                Optional.ofNullable(rs.getObject("paused_at", OffsetDateTime.class)).map(OffsetDateTime::toInstant)
                    .orElse(null),
                lastTest(rs), rs.getBoolean("accounts_required"));
    }

    private static @Nullable LastTest lastTest(ResultSet rs) throws SQLException {
        OffsetDateTime at = rs.getObject("last_tested_at", OffsetDateTime.class);
        if (at == null) {
            return null;
        }
        return new LastTest(at.toInstant(), rs.getBoolean("last_test_ok"), rs.getString("last_test_error"),
                rs.getBoolean("last_test_with_account"), rs.getObject("last_tested_by_principal_id", UUID.class),
                rs.getObject("last_test_account_id", UUID.class));
    }

    // ---- A system's pause (the package 6 contract, §5.4) ------------------------------------------------------------

    /** A paused system's switch: when, by whom, why. */
    record PauseRow(UUID systemId, String name, Instant pausedAt, @Nullable UUID pausedBy, String reason) {
    }

    void pause(UUID companyId, UUID id, Instant at, @Nullable UUID by, String reason) {
        jdbc.sql("UPDATE external_system SET paused_at = ?, paused_by = ?, paused_reason = ?, updated_at = ?"
                + " WHERE company_id = ? AND id = ?")
            .params(ts(at), by, reason, ts(at), companyId, id)
            .update();
    }

    void resume(UUID companyId, UUID id, Instant at) {
        jdbc.sql("UPDATE external_system SET paused_at = NULL, paused_by = NULL, paused_reason = NULL, updated_at = ?"
                + " WHERE company_id = ? AND id = ?")
            .params(ts(at), companyId, id)
            .update();
    }

    Optional<PauseRow> pauseOf(UUID companyId, UUID id) {
        return jdbc.sql("SELECT id, name, paused_at, paused_by, paused_reason FROM external_system"
                + " WHERE company_id = ? AND id = ? AND paused_at IS NOT NULL")
            .params(companyId, id)
            .query(SystemStore::pauseRow)
            .optional();
    }

    /** The company's paused systems, newest pause first. */
    List<PauseRow> paused(UUID companyId) {
        return jdbc.sql("SELECT id, name, paused_at, paused_by, paused_reason FROM external_system"
                + " WHERE company_id = ? AND paused_at IS NOT NULL ORDER BY paused_at DESC, id")
            .param(companyId)
            .query(SystemStore::pauseRow)
            .list();
    }

    private static PauseRow pauseRow(ResultSet rs, int row) throws SQLException {
        return new PauseRow(rs.getObject("id", UUID.class), rs.getString("name"),
                rs.getObject("paused_at", OffsetDateTime.class).toInstant(), rs.getObject("paused_by", UUID.class),
                rs.getString("paused_reason"));
    }

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
