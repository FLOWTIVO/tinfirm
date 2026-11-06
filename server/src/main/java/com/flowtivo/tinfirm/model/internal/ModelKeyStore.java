package com.flowtivo.tinfirm.model.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL access to the key rows under row-level security: a principal's in {@code principal_model_key} (V002, rotation
 * V014), the company's own in {@code company_model_key} (V045), which has the same columns but the principal's. Each
 * method takes the {@link KeyHolder} and reaches its table.
 */
@Repository
class ModelKeyStore {

    private static final String STATE = """
            secret_id, litellm_key_id, budget_usd, models, status, last_error, spent_usd, budget_resets_at, synced_at,
            sync_lease_until, created_at, updated_at, key_generation, key_created_at, key_rotated_at,
            rotation_requested_at, rotation_error, retiring_key_id, retiring_until, retiring_blocked_at,
            retiring_spend_usd, retiring_resets_at""";

    private static final String PRINCIPAL_COLUMNS = "company_id, principal_id, " + STATE;

    private static final String COMPANY_COLUMNS = "company_id, NULL::uuid AS principal_id, " + STATE;

    private static final String NOT_CREATED = "The key is not created yet";

    private final JdbcClient jdbc;

    ModelKeyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A key's status (contract §3.2). */
    enum KeyStatus {
        OK, BUDGET_SPENT, BLOCKED, ERROR
    }

    /** A key row: a principal's, or the company's own. */
    record KeyRow(KeyHolder holder, @Nullable UUID secretId, @Nullable String litellmKeyId,
            @Nullable BigDecimal budgetUsd, @Nullable List<String> models, KeyStatus status, @Nullable String lastError,
            @Nullable BigDecimal spentUsd, @Nullable Instant budgetResetsAt, @Nullable Instant syncedAt,
            @Nullable Instant syncLeaseUntil, Instant createdAt, Instant updatedAt, Rotation rotation) {

        UUID companyId() {
            return holder.companyId();
        }

        /** Whether the key exists in LiteLLM, as far as Tinfirm knows. */
        boolean hasKey() {
            return secretId != null && litellmKeyId != null;
        }
    }

    /**
     * Where a key is in its rotations (V014): the current key's generation (its alias), age and last rotation, an
     * admin's request to rotate it at once, the last rotation's failure, and the previous key while it is still in
     * LiteLLM: usable until {@code retiringUntil}, blocked at {@code retiringBlockedAt}, and the spend and reset time
     * it had when the current key was created with that spend.
     */
    record Rotation(int generation, @Nullable Instant keyCreatedAt, @Nullable Instant keyRotatedAt,
            @Nullable Instant requestedAt, @Nullable String error, @Nullable String retiringKeyId,
            @Nullable Instant retiringUntil, @Nullable Instant retiringBlockedAt, @Nullable BigDecimal retiringSpendUsd,
            @Nullable Instant retiringResetsAt) {
    }

    Optional<KeyRow> find(KeyHolder holder) {
        return jdbc.sql("SELECT " + columns(holder) + " FROM " + table(holder) + " WHERE " + where(holder))
            .params(key(holder))
            .query(ModelKeyStore::row)
            .optional();
    }

    Optional<KeyRow> lock(KeyHolder holder) {
        return jdbc.sql("SELECT " + columns(holder) + " FROM " + table(holder) + " WHERE " + where(holder)
                + " FOR UPDATE")
            .params(key(holder))
            .query(ModelKeyStore::row)
            .optional();
    }

    /** Every key row of the company: its principals' by principal, then its own key's, when it has one. */
    List<KeyRow> all(UUID companyId) {
        List<KeyRow> rows = new ArrayList<>(jdbc
            .sql("SELECT " + PRINCIPAL_COLUMNS + " FROM principal_model_key WHERE company_id = ? ORDER BY principal_id")
            .param(companyId)
            .query(ModelKeyStore::row)
            .list());
        find(KeyHolder.company(companyId)).ifPresent(rows::add);
        return rows;
    }

    /** The company's principals' key rows, by principal. */
    Map<UUID, KeyRow> principals(UUID companyId) {
        Map<UUID, KeyRow> rows = new HashMap<>();
        all(companyId).stream()
            .filter(row -> !row.holder().isCompany())
            .forEach(row -> rows.put(row.holder().requirePrincipal(), row));
        return rows;
    }

    /** A row without a key yet (status {@code ERROR}), unless the holder has one. */
    void insertPending(KeyHolder holder, Instant now) {
        if (holder.isCompany()) {
            jdbc.sql("""
                    INSERT INTO company_model_key (company_id, status, last_error, created_at, updated_at)
                    VALUES (?, 'ERROR', ?, ?, ?)
                    ON CONFLICT (company_id) DO NOTHING""")
                .params(holder.companyId(), NOT_CREATED, ts(now), ts(now))
                .update();
            return;
        }
        jdbc.sql("""
                INSERT INTO principal_model_key (principal_id, company_id, status, last_error, created_at, updated_at)
                VALUES (?, ?, 'ERROR', ?, ?, ?)
                ON CONFLICT (principal_id) DO NOTHING""")
            .params(holder.requirePrincipal(), holder.companyId(), NOT_CREATED, ts(now), ts(now))
            .update();
    }

    /**
     * Takes the row's lease until {@code until}, so that no other replica talks to LiteLLM about the key meanwhile;
     * false while another holds it.
     */
    boolean claim(KeyHolder holder, Instant now, Instant until) {
        return update(holder, "sync_lease_until = ?", "sync_lease_until IS NULL OR sync_lease_until <= ?",
                List.of(ts(until)), List.of(ts(now))) == 1;
    }

    void release(KeyHolder holder) {
        update(holder, "sync_lease_until = NULL", null, List.of(), List.of());
    }

    /**
     * The key LiteLLM created for the holder (its first, or again after LiteLLM lost one), kept in its secret: a new
     * key, whose age counts from now, and which answers an admin's request to rotate the key made before it.
     */
    void recordKey(KeyHolder holder, UUID secretId, String litellmKeyId, Instant now) {
        update(holder, """
                secret_id = ?, litellm_key_id = ?, key_created_at = ?, rotation_error = NULL,
                rotation_requested_at = CASE WHEN rotation_requested_at <= ? THEN NULL ELSE rotation_requested_at END,
                updated_at = ?""", null, values(secretId, litellmKeyId, ts(now), ts(now), ts(now)), List.of());
    }

    // ---- rotation (V014) -------------------------------------------------------------------------------------

    /**
     * An admin's request to rotate the holder's key at once: the previous key of an earlier rotation loses what is
     * left of its grace as well. False when the holder has no row.
     */
    boolean requestRotation(KeyHolder holder, Instant now) {
        return update(holder, """
                rotation_requested_at = coalesce(rotation_requested_at, ?),
                retiring_until = CASE WHEN retiring_until > ? THEN ? ELSE retiring_until END, updated_at = ?""", null,
                values(ts(now), ts(now), ts(now), ts(now)), List.of()) == 1;
    }

    /** Renews the lease of a rotation that goes on; the holder only. */
    void renewLease(KeyHolder holder, Instant until) {
        update(holder, "sync_lease_until = ?", null, values(ts(until)), List.of());
    }

    /**
     * The rotation's switch: the next key, kept in its secret, becomes the holder's, and the one it replaces is
     * retiring until {@code retiringUntil}, with the spend and reset time the next key was created with. Answers any
     * request made before.
     */
    void switchKey(KeyHolder holder, UUID secretId, String litellmKeyId, int generation, String retiringKeyId,
            Instant retiringUntil, BigDecimal retiringSpendUsd, @Nullable Instant retiringResetsAt, Instant now) {
        update(holder, """
                secret_id = ?, litellm_key_id = ?, key_generation = ?, key_created_at = ?, key_rotated_at = ?,
                rotation_requested_at = NULL, rotation_error = NULL, retiring_key_id = ?, retiring_until = ?,
                retiring_blocked_at = NULL, retiring_spend_usd = ?, retiring_resets_at = ?, updated_at = ?""", null,
                values(secretId, litellmKeyId, generation, ts(now), ts(now), retiringKeyId, ts(retiringUntil),
                        retiringSpendUsd, ts(retiringResetsAt), ts(now)),
                List.of());
    }

    /** LiteLLM blocked the previous key. */
    void retiringBlocked(KeyHolder holder, Instant now) {
        update(holder, "retiring_blocked_at = ?, rotation_error = NULL, updated_at = ?", "retiring_key_id IS NOT NULL",
                values(ts(now), ts(now)), List.of());
    }

    /**
     * LiteLLM deleted the previous key: the rotation is over. {@code spentUsd} is the current key's spend with the
     * previous key's late spend, when some went over (null: as last read).
     */
    void retired(KeyHolder holder, @Nullable BigDecimal spentUsd, Instant now) {
        update(holder, """
                retiring_key_id = NULL, retiring_until = NULL, retiring_blocked_at = NULL, retiring_spend_usd = NULL,
                retiring_resets_at = NULL, rotation_error = NULL, spent_usd = coalesce(?, spent_usd), updated_at = ?""",
                null, values(spentUsd, ts(now)), List.of());
    }

    /** Why the rotation failed, until one succeeds; releases the lease. */
    void rotationFailed(KeyHolder holder, String error, Instant now) {
        update(holder, "rotation_error = ?, sync_lease_until = NULL, updated_at = ?", null, values(error, ts(now)),
                List.of());
    }

    /** What LiteLLM last said of the key, and the status that follows; releases the lease. */
    void recordState(KeyHolder holder, KeyStatus status, @Nullable String lastError, @Nullable BigDecimal spentUsd,
            @Nullable Instant budgetResetsAt, @Nullable Instant syncedAt, Instant now) {
        update(holder, """
                status = ?, last_error = ?, spent_usd = coalesce(?, spent_usd),
                budget_resets_at = CASE WHEN ? THEN ? ELSE budget_resets_at END,
                synced_at = coalesce(?, synced_at), sync_lease_until = NULL, updated_at = ?""", null,
                values(status.name(), lastError, spentUsd, syncedAt != null, ts(budgetResetsAt), ts(syncedAt), ts(now)),
                List.of());
    }

    /** A status the relay learned from LiteLLM's answer (a spent budget, a refused key). */
    void recordStatus(KeyHolder holder, KeyStatus status, @Nullable String lastError, @Nullable Instant budgetResetsAt,
            Instant now) {
        update(holder, """
                status = ?, last_error = ?, budget_resets_at = coalesce(?, budget_resets_at), updated_at = ?""", null,
                values(status.name(), lastError, ts(budgetResetsAt), ts(now)), List.of());
    }

    /** Sets the holder's overrides; null takes the company's default. */
    void setOverrides(KeyHolder holder, @Nullable BigDecimal budgetUsd, @Nullable List<String> models, Instant now) {
        update(holder, "budget_usd = ?, models = CAST(? AS jsonb), updated_at = ?", null,
                values(budgetUsd, ModelJson.writeNullable(models), ts(now)), List.of());
    }

    void delete(KeyHolder holder) {
        jdbc.sql("DELETE FROM " + table(holder) + " WHERE " + where(holder)).params(key(holder)).update();
    }

    /**
     * Locks every key row of the company, its principals' by principal and then its own key's: its gateway changed, and
     * each key is forgotten.
     */
    List<KeyRow> lockAll(UUID companyId) {
        List<KeyRow> rows = new ArrayList<>(jdbc
            .sql("SELECT " + PRINCIPAL_COLUMNS
                    + " FROM principal_model_key WHERE company_id = ? ORDER BY principal_id FOR UPDATE")
            .param(companyId)
            .query(ModelKeyStore::row)
            .list());
        lock(KeyHolder.company(companyId)).ifPresent(rows::add);
        return rows;
    }

    /** Forgets the key of the row and the one it replaced (its gateway changed); the overrides stay. */
    void forgetKey(KeyHolder holder, Instant now) {
        update(holder, """
                secret_id = NULL, litellm_key_id = NULL, status = 'ERROR', last_error = ?, spent_usd = NULL,
                budget_resets_at = NULL, synced_at = NULL, key_created_at = NULL, rotation_requested_at = NULL,
                rotation_error = NULL, retiring_key_id = NULL, retiring_until = NULL, retiring_blocked_at = NULL,
                retiring_spend_usd = NULL, retiring_resets_at = NULL, updated_at = ?""", null,
                values(NOT_CREATED, ts(now)), List.of());
    }

    // ---- the holder's table ----------------------------------------------------------------------------------

    private static String table(KeyHolder holder) {
        return holder.isCompany() ? "company_model_key" : "principal_model_key";
    }

    private static String columns(KeyHolder holder) {
        return holder.isCompany() ? COMPANY_COLUMNS : PRINCIPAL_COLUMNS;
    }

    private static String where(KeyHolder holder) {
        return holder.isCompany() ? "company_id = ?" : "company_id = ? AND principal_id = ?";
    }

    private static Object[] key(KeyHolder holder) {
        return holder.isCompany() ? new Object[] { holder.companyId() }
                : new Object[] { holder.companyId(), holder.requirePrincipal() };
    }

    /**
     * {@code UPDATE {table} SET {set} WHERE {holder} [AND ({condition})]}, with the set's values, the holder's key and
     * the condition's values in that order; the number of rows changed.
     */
    private int update(KeyHolder holder, String set, @Nullable String condition, List<@Nullable Object> setValues,
            List<@Nullable Object> conditionValues) {
        List<@Nullable Object> params = new ArrayList<>(setValues);
        params.addAll(Arrays.asList(key(holder)));
        params.addAll(conditionValues);
        return jdbc.sql("UPDATE " + table(holder) + " SET " + set.strip() + " WHERE " + where(holder)
                + (condition != null ? " AND (" + condition + ")" : ""))
            .params(params)
            .update();
    }

    /** The values of a statement, nulls among them. */
    private static List<@Nullable Object> values(@Nullable Object... values) {
        return Arrays.asList(values);
    }

    private static KeyRow row(ResultSet rs, int row) throws SQLException {
        UUID companyId = rs.getObject("company_id", UUID.class);
        UUID principalId = rs.getObject("principal_id", UUID.class);
        return new KeyRow(new KeyHolder(companyId, principalId), rs.getObject("secret_id", UUID.class),
                rs.getString("litellm_key_id"), rs.getBigDecimal("budget_usd"),
                ModelJson.nullableStrings(rs.getString("models")), KeyStatus.valueOf(rs.getString("status")),
                rs.getString("last_error"), rs.getBigDecimal("spent_usd"), instant(rs, "budget_resets_at"),
                instant(rs, "synced_at"), instant(rs, "sync_lease_until"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                new Rotation(rs.getInt("key_generation"), instant(rs, "key_created_at"), instant(rs, "key_rotated_at"),
                        instant(rs, "rotation_requested_at"), rs.getString("rotation_error"),
                        rs.getString("retiring_key_id"), instant(rs, "retiring_until"),
                        instant(rs, "retiring_blocked_at"), rs.getBigDecimal("retiring_spend_usd"),
                        instant(rs, "retiring_resets_at")));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
