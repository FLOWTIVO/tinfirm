package com.flowtivo.tinfirm.trigger.internal;

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
import org.springframework.stereotype.Component;

/**
 * SQL access to {@code webhook_delivery} (V019, the package 5 contract, §3.12) and the reads a webhook's firing needs:
 * its echoes ({@code system_write} and {@code trigger_firing}). Under row-level security, in the caller's transaction
 * and tenant context.
 */
@Component
class WebhookStore {

    private final JdbcClient jdbc;

    WebhookStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A delivery as kept. */
    record Delivery(UUID id, UUID companyId, UUID systemId, String deliveryId, @Nullable Instant signedAt,
            Instant receivedAt, String status, @Nullable String event, @Nullable String subjectKey, int firings,
            @Nullable String failure, @Nullable String body, int bodyBytes) {
    }

    /**
     * A new delivery, {@code NO_TRIGGER} until its firings say otherwise; empty when its id is known already for the
     * system (C3): {@code ON CONFLICT DO NOTHING}.
     */
    Optional<UUID> insert(UUID id, UUID companyId, UUID systemId, String deliveryId, @Nullable Instant signedAt,
            Instant receivedAt, String body, int bodyBytes) {
        return jdbc.sql("""
                INSERT INTO webhook_delivery (id, company_id, system_id, delivery_id, signed_at, received_at, status,
                                              body, body_bytes)
                VALUES (?, ?, ?, ?, ?, ?, 'NO_TRIGGER', CAST(? AS jsonb), ?)
                ON CONFLICT (system_id, delivery_id) DO NOTHING
                RETURNING id""")
            .params(id, companyId, systemId, deliveryId, ts(signedAt), ts(receivedAt), body, bodyBytes)
            .query(UUID.class)
            .optional();
    }

    /** What the delivery did. */
    void finish(UUID companyId, UUID id, String status, @Nullable String event, @Nullable String subjectKey,
            int firings, @Nullable String failure) {
        jdbc.sql("""
                UPDATE webhook_delivery SET status = ?, event = ?, subject_key = ?, firings = ?, failure = ?
                 WHERE company_id = ? AND id = ?""")
            .params(status, event, subjectKey, firings, failure != null && failure.length() > 500
                    ? failure.substring(0, 499) + "…" : failure, companyId, id)
            .update();
    }

    private static final String COLUMNS = "id, company_id, system_id, delivery_id, signed_at, received_at, status,"
            + " event, subject_key, firings, failure, body::text AS body, body_bytes";

    /** The system's deliveries, newest first, after the keyset. */
    List<Delivery> page(UUID companyId, UUID systemId, @Nullable Instant afterAt, @Nullable UUID afterId,
            int limit) {
        if (afterAt == null || afterId == null) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_delivery WHERE company_id = ? AND system_id = ?"
                    + " ORDER BY received_at DESC, id DESC LIMIT ?")
                .params(companyId, systemId, limit)
                .query(WebhookStore::delivery)
                .list();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM webhook_delivery WHERE company_id = ? AND system_id = ?"
                + " AND (received_at, id) < (?, ?) ORDER BY received_at DESC, id DESC LIMIT ?")
            .params(companyId, systemId, ts(afterAt), afterId, limit)
            .query(WebhookStore::delivery)
            .list();
    }

    /** A system's deliveries since a time: how many, and how many of them the mapping could not read. */
    record Counts(int deliveries, int failed) {
    }

    /** The system's deliveries received since the time, on {@code webhook_delivery_list_idx}. */
    Counts counts(UUID companyId, UUID systemId, Instant since) {
        return jdbc.sql("""
                SELECT count(*) AS deliveries, count(*) FILTER (WHERE status = 'MAPPING_FAILED') AS failed
                  FROM webhook_delivery WHERE company_id = ? AND system_id = ? AND received_at >= ?""")
            .params(companyId, systemId, ts(since))
            .query((rs, row) -> new Counts(rs.getInt("deliveries"), rs.getInt("failed")))
            .single();
    }

    /** The conversation an echo came from and the deepest firing it took. */
    record Echo(UUID conversationId, int depth) {
    }

    /**
     * Whether a delivery about the subject key is an echo (§5.12): a conversation of the trigger on that key (by its
     * firings) that recorded a write since, or that wrote to that key since ({@code system_write}); the deepest such.
     */
    Optional<Echo> echo(UUID companyId, UUID triggerId, String subjectKey, Instant since) {
        return jdbc.sql("""
                SELECT f.conversation_id, max(f.chain_depth) AS depth
                  FROM trigger_firing f
                 WHERE f.company_id = :companyId AND f.trigger_id = :triggerId AND f.conversation_id IS NOT NULL
                   AND EXISTS (
                       SELECT 1 FROM system_write w
                        WHERE w.company_id = :companyId AND w.conversation_id = f.conversation_id
                          AND w.written_at >= :since
                          AND (w.subject_key = :subjectKey OR EXISTS (
                              SELECT 1 FROM trigger_firing g
                               WHERE g.company_id = :companyId AND g.conversation_id = f.conversation_id
                                 AND g.subject_key = :subjectKey)))
                 GROUP BY f.conversation_id
                 ORDER BY depth DESC, f.conversation_id
                 LIMIT 1""")
            .param("companyId", companyId)
            .param("triggerId", triggerId)
            .param("since", ts(since))
            .param("subjectKey", subjectKey)
            .query((rs, n) -> new Echo(rs.getObject("conversation_id", UUID.class), rs.getInt("depth")))
            .optional();
    }

    // ---- retention (§3.12: the body for 24 hours, the row for 7 days) ------------------------------------------

    /** Deletes the company's rows past their 7 days and clears the bodies past their 24 hours. */
    long sweep(UUID companyId, Instant bodiesBefore, Instant rowsBefore) {
        int deleted = jdbc.sql("DELETE FROM webhook_delivery WHERE company_id = ? AND received_at < ?")
            .params(companyId, ts(rowsBefore))
            .update();
        int cleared = jdbc.sql("UPDATE webhook_delivery SET body = NULL WHERE company_id = ? AND body IS NOT NULL"
                + " AND received_at < ?")
            .params(companyId, ts(bodiesBefore))
            .update();
        return (long) deleted + cleared;
    }

    private static Delivery delivery(ResultSet rs, int row) throws SQLException {
        return new Delivery(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getString("delivery_id"), instant(rs, "signed_at"),
                java.util.Objects.requireNonNull(instant(rs, "received_at")), rs.getString("status"),
                rs.getString("event"), rs.getString("subject_key"), rs.getInt("firings"), rs.getString("failure"),
                rs.getString("body"), rs.getInt("body_bytes"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
