package com.flowtivo.tinfirm.audit.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL access to {@code audit_event} and {@code audit_chain_head}. Row-level security decides which chains the
 * caller can read and append to; the runtime role cannot update or delete events.
 */
@Repository
class AuditEventStore {

    static final String COLUMNS = """
            id, company_id, seq, occurred_at, actor_type, actor_id, action, target_type, target_id,
            details::text AS details, prev_hash, hash""";

    private final JdbcClient jdbc;

    AuditEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Locks the chain head until the end of the transaction. */
    Optional<ChainHead> lockHead(@Nullable UUID companyId) {
        return jdbc.sql("SELECT last_seq, last_hash FROM audit_chain_head WHERE " + chain(companyId) + " FOR UPDATE")
            .params(chainParams(companyId))
            .query(ChainHead.class)
            .optional();
    }

    Optional<ChainHead> head(@Nullable UUID companyId) {
        return jdbc.sql("SELECT last_seq, last_hash FROM audit_chain_head WHERE " + chain(companyId))
            .params(chainParams(companyId))
            .query(ChainHead.class)
            .optional();
    }

    void createHeadIfAbsent(@Nullable UUID companyId) {
        jdbc.sql("""
                INSERT INTO audit_chain_head (company_id, last_seq, last_hash)
                VALUES (?, 0, ?)
                ON CONFLICT DO NOTHING""")
            .params(uuid(companyId), AuditCanonicalForm.GENESIS_HASH)
            .update();
    }

    void advanceHead(@Nullable UUID companyId, long seq, String hash) {
        int updated = jdbc.sql("UPDATE audit_chain_head SET last_seq = ?, last_hash = ? WHERE " + chain(companyId))
            .params(prepend(chainParams(companyId), seq, hash))
            .update();
        if (updated != 1) {
            throw new IllegalStateException("Audit chain head could not be advanced");
        }
    }

    void insert(StoredAuditEvent event) {
        jdbc.sql("""
                INSERT INTO audit_event (id, company_id, seq, occurred_at, actor_type, actor_id, action,
                                         target_type, target_id, details, prev_hash, hash)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)""")
            .params(event.id(), uuid(event.companyId()), event.seq(), event.occurredAt().atOffset(ZoneOffset.UTC),
                    event.actorType(), uuid(event.actorId()), event.action(), event.targetType(), event.targetId(),
                    event.detailsJson(), event.prevHash(), event.hash())
            .update();
    }

    /**
     * Newest first: events of the chain with {@code seq < beforeSeq} (all when null) from {@code since} on (all when
     * null), what every edition shows. The audit's filters read with their own query, under {@code ee/}
     * ({@link AuditTools}).
     */
    List<StoredAuditEvent> newestFirst(@Nullable UUID companyId, @Nullable Instant since, @Nullable Long beforeSeq,
            int limit) {
        StringBuilder where = new StringBuilder(chain(companyId));
        List<Object> params = new ArrayList<>(chainParams(companyId));
        if (beforeSeq != null) {
            where.append(" AND seq < ?");
            params.add(beforeSeq);
        }
        if (since != null) {
            where.append(" AND occurred_at >= ?");
            params.add(since.atOffset(ZoneOffset.UTC));
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_event WHERE " + where + " ORDER BY seq DESC LIMIT ?")
            .params(append(params, limit))
            .query(AuditEventStore::map)
            .list();
    }

    /**
     * The principals among these whose content the company erased after they left: those with a
     * {@code principal.erased} whose {@code left} is true (CONVENTIONS §8). The chain is the record of an erasure,
     * never purged; on {@code audit_event_target_idx}.
     */
    Set<UUID> erasedAfterLeaving(UUID companyId, Collection<UUID> principalIds) {
        if (principalIds.isEmpty()) {
            return Set.of();
        }
        Set<UUID> erased = new HashSet<>();
        jdbc.sql("""
                SELECT DISTINCT target_id
                  FROM audit_event
                 WHERE company_id = :companyId AND target_type = 'principal' AND target_id IN (:ids)
                   AND action = 'principal.erased' AND details ->> 'left' = 'true'""")
            .param("companyId", companyId)
            .param("ids", principalIds.stream().map(UUID::toString).distinct().toList())
            .query(String.class)
            .list()
            .forEach(id -> erased.add(UUID.fromString(id)));
        return erased;
    }

    /** The event of the chain at this position. */
    Optional<StoredAuditEvent> at(@Nullable UUID companyId, long seq) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_event WHERE " + chain(companyId) + " AND seq = ?")
            .params(append(chainParams(companyId), seq))
            .query(AuditEventStore::map)
            .optional();
    }

    /** The chain's last event as stored, whatever its head row says. */
    Optional<StoredAuditEvent> last(@Nullable UUID companyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_event WHERE " + chain(companyId)
                + " ORDER BY seq DESC LIMIT 1")
            .params(chainParams(companyId))
            .query(AuditEventStore::map)
            .optional();
    }

    /**
     * The sum of the {@code count}s of the webhook system's {@code webhook.refused} events since the time (an event
     * without one counts once), on {@code audit_event_webhook_refused_idx} (V043).
     */
    long webhookRefusals(UUID companyId, UUID systemId, Instant since) {
        return jdbc.sql("""
                SELECT coalesce(sum(CASE WHEN jsonb_typeof(details -> 'count') = 'number'
                                         THEN (details ->> 'count')::bigint ELSE 1 END), 0)
                  FROM audit_event
                 WHERE company_id = ? AND action = 'webhook.refused' AND target_id = ? AND occurred_at >= ?
                   AND target_type = 'system'""")
            .params(companyId, systemId.toString(), since.atOffset(ZoneOffset.UTC))
            .query(Long.class)
            .single();
    }

    /** One reader of a conversation, from its {@code conversation.read} events. */
    record ReaderRow(UUID userId, Instant first, Instant last, long reads, @Nullable String kinds) {
    }

    /** The readers of the conversation, the most recent first. */
    List<ReaderRow> conversationReaders(UUID companyId, UUID conversationId, int limit) {
        return jdbc.sql("""
                SELECT actor_id, min(occurred_at) AS first_at, max(occurred_at) AS last_at, count(*) AS reads,
                       string_agg(DISTINCT coalesce(details ->> 'what', details ->> 'kind'), ','
                                  ORDER BY coalesce(details ->> 'what', details ->> 'kind')) AS kinds
                  FROM audit_event
                 WHERE company_id = ? AND details ->> 'conversationId' = ?
                   AND action = 'conversation.read' AND actor_type = 'USER' AND actor_id IS NOT NULL
                 GROUP BY actor_id
                 ORDER BY max(occurred_at) DESC, actor_id
                 LIMIT ?""")
            .params(companyId, conversationId.toString(), limit)
            .query((rs, row) -> new ReaderRow(rs.getObject("actor_id", UUID.class),
                    rs.getObject("first_at", OffsetDateTime.class).toInstant(),
                    rs.getObject("last_at", OffsetDateTime.class).toInstant(), rs.getLong("reads"),
                    rs.getString("kinds")))
            .list();
    }

    /** Oldest first: events of the chain with {@code afterSeq < seq <= maxSeq}. */
    List<StoredAuditEvent> chainSlice(@Nullable UUID companyId, long afterSeq, long maxSeq, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_event WHERE " + chain(companyId)
                + " AND seq > ? AND seq <= ? ORDER BY seq LIMIT ?")
            .params(append(chainParams(companyId), afterSeq, maxSeq, limit))
            .query(AuditEventStore::map)
            .list();
    }

    static StoredAuditEvent map(ResultSet rs, int row) throws SQLException {
        return new StoredAuditEvent(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getLong("seq"), rs.getObject("occurred_at", OffsetDateTime.class).toInstant(),
                rs.getString("actor_type"), rs.getObject("actor_id", UUID.class), rs.getString("action"),
                rs.getString("target_type"), rs.getString("target_id"), rs.getString("details"),
                rs.getString("prev_hash"), rs.getString("hash"));
    }

    // Separate predicates keep both chains on the (company_id, seq) index; IS NOT DISTINCT FROM would not.
    static String chain(@Nullable UUID companyId) {
        return companyId == null ? "company_id IS NULL" : "company_id = ?";
    }

    static List<Object> chainParams(@Nullable UUID companyId) {
        return companyId == null ? List.of() : List.of(companyId);
    }

    private static List<Object> prepend(List<Object> params, Object... first) {
        ArrayList<Object> all = new ArrayList<>(List.of(first));
        all.addAll(params);
        return all;
    }

    static List<Object> append(List<Object> params, Object... last) {
        ArrayList<Object> all = new ArrayList<>(params);
        all.addAll(List.of(last));
        return all;
    }

    private static SqlParameterValue uuid(@Nullable UUID value) {
        return new SqlParameterValue(Types.OTHER, value);
    }
}
