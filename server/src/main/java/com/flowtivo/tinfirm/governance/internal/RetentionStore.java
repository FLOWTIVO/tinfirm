package com.flowtivo.tinfirm.governance.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.conversation.ConversationPurge;

/**
 * SQL access to {@code retention_settings}, {@code retention_sweep} and {@code legal_hold} (V023), under row-level
 * security: the company of the tenant context only. The writes only a paid feature makes, the OWNER's periods and a
 * hold set, are {@code RetentionWrites} under {@code ee/} (ADR-0077, decision 1).
 */
@Repository
class RetentionStore {

    private static final String HOLD_COLUMNS = "id, company_id, conversation_id, principal_id, reason, set_at, set_by,"
            + " lifted_at, lifted_by";

    private final JdbcClient jdbc;

    RetentionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The company's settings as stored; empty when its OWNER never set them (the defaults). */
    record SettingsRow(Periods periods, Instant updatedAt, @Nullable UUID updatedBy) {
    }

    Optional<SettingsRow> settings(UUID companyId) {
        return jdbc.sql("""
                SELECT run_events_days, chat_content_days, content_days, files_days, memory_days, updated_at,
                       updated_by
                  FROM retention_settings WHERE company_id = ?""")
            .params(companyId)
            .query((rs, row) -> new SettingsRow(new Periods(rs.getObject("run_events_days", Integer.class),
                    rs.getObject("chat_content_days", Integer.class), rs.getObject("content_days", Integer.class),
                    rs.getObject("files_days", Integer.class), rs.getObject("memory_days", Integer.class)),
                    rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("updated_by", UUID.class)))
            .optional();
    }

    /** The company's periods: its OWNER's, else the defaults. */
    Periods periods(UUID companyId) {
        return settings(companyId).map(SettingsRow::periods).orElse(Periods.DEFAULTS);
    }

    /** When the sweep last ran in the company. */
    Optional<Instant> lastSwept(UUID companyId) {
        return jdbc.sql("SELECT swept_at FROM retention_sweep WHERE company_id = ?")
            .params(companyId)
            .query((rs, row) -> rs.getObject("swept_at", OffsetDateTime.class).toInstant())
            .optional();
    }

    void recordSweep(UUID companyId, Instant at, long openOlder) {
        jdbc.sql("""
                INSERT INTO retention_sweep (company_id, swept_at, open_older) VALUES (?, ?, ?)
                ON CONFLICT (company_id) DO UPDATE SET swept_at = EXCLUDED.swept_at, open_older = EXCLUDED.open_older""")
            .params(companyId, ts(at), (int) Math.min(openOlder, Integer.MAX_VALUE))
            .update();
    }

    /** The companies whose last sweep is older than the cutoff (V023's {@code retention_due}). */
    List<UUID> due(Instant sweptBefore, int limit) {
        return jdbc.sql("SELECT company_id FROM retention_due(?, ?)")
            .params(ts(sweptBefore), limit)
            .query(UUID.class)
            .list();
    }

    // ---- legal holds -----------------------------------------------------------------------------------------------

    /** A legal hold: on a conversation or on a principal. */
    record HoldRow(UUID id, UUID companyId, @Nullable UUID conversationId, @Nullable UUID principalId, String reason,
            Instant setAt, @Nullable UUID setBy, @Nullable Instant liftedAt, @Nullable UUID liftedBy) {

        boolean open() {
            return liftedAt == null;
        }
    }

    /**
     * Serialises the company's holds against what purges: a purge takes it shared and reads the open holds under it,
     * a hold's change exclusively, so that no purge removes what a hold set meanwhile covers (G16).
     */
    void lockHolds(UUID companyId, boolean exclusive) {
        jdbc.sql("SELECT " + (exclusive ? "pg_advisory_xact_lock" : "pg_advisory_xact_lock_shared")
                + "(hashtextextended(?, 0))")
            .params("legal_hold:" + companyId)
            .query((rs, row) -> 1)
            .list();
    }

    /** What the company's open holds cover. */
    ConversationPurge.Held held() {
        Set<UUID> conversations = new HashSet<>();
        Set<UUID> principals = new HashSet<>();
        jdbc.sql("SELECT conversation_id, principal_id FROM legal_hold WHERE lifted_at IS NULL")
            .query((rs, row) -> {
                UUID conversation = rs.getObject("conversation_id", UUID.class);
                if (conversation != null) {
                    conversations.add(conversation);
                }
                UUID principal = rs.getObject("principal_id", UUID.class);
                if (principal != null) {
                    principals.add(principal);
                }
                return row;
            })
            .list();
        return new ConversationPurge.Held(conversations, principals);
    }

    /** The open holds on these conversations or principals. */
    List<HoldRow> openHoldsOn(Set<UUID> conversationIds, Set<UUID> principalIds) {
        return jdbc.sql("SELECT " + HOLD_COLUMNS + """
                 FROM legal_hold
                WHERE lifted_at IS NULL
                  AND (conversation_id = ANY (CAST(? AS uuid[])) OR principal_id = ANY (CAST(? AS uuid[])))
                ORDER BY set_at, id""")
            .params(ids(conversationIds), ids(principalIds))
            .query(RetentionStore::hold)
            .list();
    }

    Optional<HoldRow> hold(UUID holdId) {
        return jdbc.sql("SELECT " + HOLD_COLUMNS + " FROM legal_hold WHERE id = ?")
            .params(holdId)
            .query(RetentionStore::hold)
            .optional();
    }

    boolean lift(UUID holdId, Instant at, UUID userId) {
        return jdbc.sql("UPDATE legal_hold SET lifted_at = ?, lifted_by = ? WHERE id = ? AND lifted_at IS NULL")
            .params(ts(at), userId, holdId)
            .update() == 1;
    }

    /** Newest first, after the hold with this key ({@code setAt}, {@code id}); the lifted ones too when asked. */
    List<HoldRow> holds(boolean lifted, @Nullable Instant beforeAt, @Nullable UUID beforeId, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT " + HOLD_COLUMNS + " FROM legal_hold WHERE true");
        if (!lifted) {
            sql.append(" AND lifted_at IS NULL");
        }
        if (beforeAt != null && beforeId != null) {
            sql.append(" AND (set_at, id) < (?, ?)");
            params.add(ts(beforeAt));
            params.add(beforeId);
        }
        sql.append(" ORDER BY set_at DESC, id DESC LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(RetentionStore::hold).list();
    }

    /** Deletes the holds lifted before the cutoff; answers how many. */
    long deleteLifted(Instant liftedBefore) {
        return jdbc.sql("DELETE FROM legal_hold WHERE lifted_at IS NOT NULL AND lifted_at < ?")
            .params(ts(liftedBefore))
            .update();
    }

    private static HoldRow hold(ResultSet rs, int row) throws SQLException {
        OffsetDateTime lifted = rs.getObject("lifted_at", OffsetDateTime.class);
        return new HoldRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getObject("principal_id", UUID.class),
                rs.getString("reason"), rs.getObject("set_at", OffsetDateTime.class).toInstant(),
                rs.getObject("set_by", UUID.class), lifted != null ? lifted.toInstant() : null,
                rs.getObject("lifted_by", UUID.class));
    }

    private static String[] ids(Set<UUID> ids) {
        return ids.stream().map(UUID::toString).toArray(String[]::new);
    }

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
