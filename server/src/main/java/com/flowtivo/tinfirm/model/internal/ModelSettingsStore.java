package com.flowtivo.tinfirm.model.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;


/**
 * SQL access to {@code model_settings} (V002), under row-level security, and the cross-company lookup of the companies
 * whose gateway passed its test ({@code model_settings_companies}).
 */
@Repository
class ModelSettingsStore {

    private static final String COLUMNS = """
            company_id, gateway, vendor, admin_url, model_url, member_keys, admin_key_secret_id, relay_enabled,
            default_budget_usd, budget_period, default_models, tested_at, last_error, updated_at, updated_by""";

    private final JdbcClient jdbc;

    ModelSettingsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * {@code BUNDLED}, {@code EXTERNAL}, or from V082 {@code PROVIDED}: a company's own gateway whose keys its gateway
     * team provides (ADR-0094).
     */
    enum GatewayKind {
        BUNDLED, EXTERNAL, PROVIDED
    }

    /**
     * A company's settings; {@code vendor} the product its gateway is (V081, ADR-0094), {@code modelUrl} a
     * {@code PROVIDED} gateway's root for model requests and {@code memberKeys} whether its members enter their own key
     * (V082).
     */
    record SettingsRow(UUID companyId, GatewayKind gateway, GatewayVendor vendor, @Nullable String adminUrl,
            @Nullable String modelUrl, boolean memberKeys, @Nullable UUID adminKeySecretId,
            boolean relayEnabled, BigDecimal defaultBudgetUsd, String budgetPeriod, List<String> defaultModels,
            @Nullable Instant testedAt, @Nullable String lastError, Instant updatedAt, @Nullable UUID updatedBy) {

        SettingsRow {
            defaultModels = List.copyOf(defaultModels);
        }
    }

    Optional<SettingsRow> find(UUID companyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM model_settings WHERE company_id = ?")
            .param(companyId)
            .query(ModelSettingsStore::row)
            .optional();
    }

    Optional<SettingsRow> lock(UUID companyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM model_settings WHERE company_id = ? FOR UPDATE")
            .param(companyId)
            .query(ModelSettingsStore::row)
            .optional();
    }

    void upsert(SettingsRow row) {
        jdbc.sql("""
                INSERT INTO model_settings (company_id, gateway, vendor, admin_url, model_url, member_keys,
                                            admin_key_secret_id, relay_enabled, default_budget_usd, budget_period,
                                            default_models, tested_at, last_error, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)
                ON CONFLICT (company_id) DO UPDATE
                   SET gateway = EXCLUDED.gateway, vendor = EXCLUDED.vendor, admin_url = EXCLUDED.admin_url,
                       model_url = EXCLUDED.model_url, member_keys = EXCLUDED.member_keys,
                       admin_key_secret_id = EXCLUDED.admin_key_secret_id, relay_enabled = EXCLUDED.relay_enabled,
                       default_budget_usd = EXCLUDED.default_budget_usd, budget_period = EXCLUDED.budget_period,
                       default_models = EXCLUDED.default_models, tested_at = EXCLUDED.tested_at,
                       last_error = EXCLUDED.last_error, updated_at = EXCLUDED.updated_at,
                       updated_by = EXCLUDED.updated_by""")
            .params(row.companyId(), row.gateway().name(), row.vendor().name(), row.adminUrl(), row.modelUrl(),
                    row.memberKeys(), row.adminKeySecretId(), row.relayEnabled(), row.defaultBudgetUsd(),
                    row.budgetPeriod(), ModelJson.write(row.defaultModels()), ts(row.testedAt()), row.lastError(),
                    ts(row.updatedAt()), row.updatedBy())
            .update();
    }

    /** Records a test of the gateway: whether it passed (which enables or disables the relay) and why not. */
    void recordTest(UUID companyId, boolean ok, @Nullable String error, Instant at) {
        jdbc.sql("""
                UPDATE model_settings SET relay_enabled = ?, tested_at = ?, last_error = ?
                 WHERE company_id = ?""")
            .params(ok, ts(at), error, companyId)
            .update();
    }

    /** Whether the company's settings keep {@code secretId} as the admin key of their gateway. */
    boolean keepsAdminKey(UUID companyId, UUID secretId) {
        return jdbc.sql("SELECT 1 FROM model_settings WHERE company_id = ? AND admin_key_secret_id = ?")
            .params(companyId, secretId)
            .query(Integer.class)
            .optional()
            .isPresent();
    }

    /** The companies whose gateway passed its test, across companies (ids only). */
    List<UUID> enabledCompanies() {
        return jdbc.sql("SELECT company_id FROM model_settings_companies()").query(UUID.class).list();
    }

    private static SettingsRow row(ResultSet rs, int row) throws SQLException {
        return new SettingsRow(rs.getObject("company_id", UUID.class),
                GatewayKind.valueOf(rs.getString("gateway")), GatewayVendor.valueOf(rs.getString("vendor")),
                rs.getString("admin_url"), rs.getString("model_url"), rs.getBoolean("member_keys"),
                rs.getObject("admin_key_secret_id", UUID.class), rs.getBoolean("relay_enabled"),
                rs.getBigDecimal("default_budget_usd"), rs.getString("budget_period"),
                ModelJson.strings(rs.getString("default_models")), instant(rs, "tested_at"), rs.getString("last_error"),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getObject("updated_by", UUID.class));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
