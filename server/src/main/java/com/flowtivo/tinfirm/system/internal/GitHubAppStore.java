package com.flowtivo.tinfirm.system.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access to {@code system_github_app} (V088): a {@code GITHUB} system's one GitHub App (ADR-0116). */
@Repository
class GitHubAppStore {

    private static final String COLUMNS = "system_id, company_id, app_id, app_slug, app_name, private_key_secret_id,"
            + " set_at, set_by";

    private final JdbcClient jdbc;

    GitHubAppStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The App's row; its key is the managed secret {@code privateKeySecretId}. */
    record AppRow(UUID systemId, UUID companyId, long appId, @Nullable String slug, @Nullable String name,
            UUID privateKeySecretId, Instant setAt, @Nullable UUID setBy) {
    }

    Optional<AppRow> find(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM system_github_app WHERE company_id = ? AND system_id = ?")
            .params(companyId, systemId)
            .query(GitHubAppStore::row)
            .optional();
    }

    Optional<AppRow> lock(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM system_github_app WHERE company_id = ? AND system_id = ?"
                + " FOR UPDATE")
            .params(companyId, systemId)
            .query(GitHubAppStore::row)
            .optional();
    }

    /** The row, locked {@code FOR SHARE}: no {@code PUT} or {@code DELETE} of the App passes until the commit. */
    Optional<AppRow> lockShared(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM system_github_app WHERE company_id = ? AND system_id = ?"
                + " FOR SHARE")
            .params(companyId, systemId)
            .query(GitHubAppStore::row)
            .optional();
    }

    Optional<AppRow> withSecret(UUID companyId, UUID secretId) {
        return jdbc.sql("SELECT " + COLUMNS
                + " FROM system_github_app WHERE company_id = ? AND private_key_secret_id = ?")
            .params(companyId, secretId)
            .query(GitHubAppStore::row)
            .optional();
    }

    void upsert(AppRow app) {
        jdbc.sql("""
                INSERT INTO system_github_app (system_id, company_id, app_id, app_slug, app_name, private_key_secret_id,
                                               set_at, set_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (system_id) DO UPDATE SET app_id = EXCLUDED.app_id, app_slug = EXCLUDED.app_slug,
                    app_name = EXCLUDED.app_name, private_key_secret_id = EXCLUDED.private_key_secret_id,
                    set_at = EXCLUDED.set_at, set_by = EXCLUDED.set_by""")
            .params(app.systemId(), app.companyId(), app.appId(), app.slug(), app.name(), app.privateKeySecretId(),
                    app.setAt().atOffset(ZoneOffset.UTC), app.setBy())
            .update();
    }

    void delete(UUID companyId, UUID systemId) {
        jdbc.sql("DELETE FROM system_github_app WHERE company_id = ? AND system_id = ?")
            .params(companyId, systemId)
            .update();
    }

    private static AppRow row(ResultSet rs, int row) throws SQLException {
        return new AppRow(rs.getObject("system_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getLong("app_id"), rs.getString("app_slug"), rs.getString("app_name"),
                rs.getObject("private_key_secret_id", UUID.class), rs.getTimestamp("set_at").toInstant(),
                rs.getObject("set_by", UUID.class));
    }
}
