package com.flowtivo.tinfirm.audit.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL access to the anchor's tables (V084): what the writer wrote, never what verification trusts. The state and the
 * checkpoints are the instance's (instance admins, server-side work); a chain's rows of {@code audit_checkpoint_chain}
 * are visible where its events are.
 */
@Repository
class AuditCheckpointStore {

    private final JdbcClient jdbc;

    AuditCheckpointStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The writer's state. */
    record State(UUID instanceId, @Nullable Long lastNumber, @Nullable Instant lastAttemptAt, @Nullable Instant lastSuccessAt,
            @Nullable Instant lastFailureAt, @Nullable String lastFailure, int consecutiveFailures) {
    }

    /** A checkpoint written, as recorded. */
    record Written(long number, Instant issuedAt, String keyId, String digest, String location, String document,
            int chains) {
    }

    /** The writer's lock, for the transaction: one replica writes at a time. */
    boolean tryLock() {
        Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended('audit_anchor', 0))")
            .query(Boolean.class)
            .single();
        return Boolean.TRUE.equals(locked);
    }

    Optional<State> state() {
        return jdbc.sql("""
                SELECT instance_id, last_number, last_attempt_at, last_success_at, last_failure_at, last_failure,
                       consecutive_failures
                  FROM audit_anchor_state WHERE id = 1""")
            .query((rs, row) -> new State(rs.getObject("instance_id", UUID.class),
                    rs.getObject("last_number", Long.class), instant(rs, "last_attempt_at"),
                    instant(rs, "last_success_at"), instant(rs, "last_failure_at"), rs.getString("last_failure"),
                    rs.getInt("consecutive_failures")))
            .optional();
    }

    /** The state, made with a new instance id on the first call. */
    State stateOrCreate(UUID newInstanceId) {
        jdbc.sql("INSERT INTO audit_anchor_state (id, instance_id) VALUES (1, ?) ON CONFLICT (id) DO NOTHING")
            .params(newInstanceId)
            .update();
        return state().orElseThrow();
    }

    void recordSuccess(long number, Instant at) {
        jdbc.sql("""
                UPDATE audit_anchor_state
                   SET last_number = ?, last_attempt_at = ?, last_success_at = ?, consecutive_failures = 0
                 WHERE id = 1""")
            .params(number, ts(at), ts(at))
            .update();
    }

    /** A look that found nothing to write: the writer is well, and nothing changed. */
    void recordNothingToWrite(Instant at) {
        jdbc.sql("UPDATE audit_anchor_state SET last_attempt_at = ?, consecutive_failures = 0 WHERE id = 1")
            .params(ts(at))
            .update();
    }

    void recordFailure(Instant at, String reason) {
        jdbc.sql("""
                UPDATE audit_anchor_state
                   SET last_attempt_at = ?, last_failure_at = ?, last_failure = ?,
                       consecutive_failures = consecutive_failures + 1
                 WHERE id = 1""")
            .params(ts(at), ts(at), reason.length() > 1000 ? reason.substring(0, 1000) : reason)
            .update();
    }

    /** The latest checkpoint recorded. */
    Optional<Written> latest() {
        return jdbc.sql("""
                SELECT number, issued_at, key_id, digest, location, document, chains
                  FROM audit_checkpoint ORDER BY number DESC LIMIT 1""")
            .query((rs, row) -> new Written(rs.getLong("number"), instant(rs, "issued_at"), rs.getString("key_id"),
                    rs.getString("digest"), rs.getString("location"), rs.getString("document"), rs.getInt("chains")))
            .optional();
    }

    /** A checkpoint as recorded, by its number. */
    Optional<Written> written(long number) {
        return jdbc.sql("""
                SELECT number, issued_at, key_id, digest, location, document, chains
                  FROM audit_checkpoint WHERE number = ?""")
            .params(number)
            .query((rs, row) -> new Written(rs.getLong("number"), instant(rs, "issued_at"), rs.getString("key_id"),
                    rs.getString("digest"), rs.getString("location"), rs.getString("document"), rs.getInt("chains")))
            .optional();
    }

    void insert(Checkpoint checkpoint, String digest, String location, String document, Instant writtenAt) {
        jdbc.sql("""
                INSERT INTO audit_checkpoint (number, issued_at, key_id, chains, digest, location, document,
                                              written_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (number) DO NOTHING""")
            .params(checkpoint.number(), ts(checkpoint.issuedAt()), checkpoint.keyId(), checkpoint.chains().size(),
                    digest, location, document, ts(writtenAt))
            .update();
        for (Checkpoint.Head head : checkpoint.chains()) {
            jdbc.sql("""
                    INSERT INTO audit_checkpoint_chain (number, company_id, seq, hash, issued_at, key_id)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT DO NOTHING""")
                .params(checkpoint.number(), uuid(head.companyId()), head.seq(), head.hash(),
                        ts(checkpoint.issuedAt()), checkpoint.keyId())
                .update();
        }
    }

    /** Every chain's head row, the instance's first: server-side work across companies ({@code app.system}). */
    List<Checkpoint.Head> heads() {
        return jdbc.sql("SELECT company_id, last_seq, last_hash FROM audit_chain_head WHERE last_seq > 0")
            .query((rs, row) -> new Checkpoint.Head(rs.getObject("company_id", UUID.class), rs.getLong("last_seq"),
                    rs.getString("last_hash")))
            .list();
    }

    /** The heads the latest checkpoint of each chain names, as recorded. */
    List<Checkpoint.Head> lastAnchored() {
        return jdbc.sql("""
                SELECT DISTINCT ON (company_id) company_id, seq, hash
                  FROM audit_checkpoint_chain ORDER BY company_id, number DESC""")
            .query((rs, row) -> new Checkpoint.Head(rs.getObject("company_id", UUID.class), rs.getLong("seq"),
                    rs.getString("hash")))
            .list();
    }

    /** The latest checkpoint recorded for the chain, at or before the seq when one is given. */
    Optional<AuditAnchors.Anchored> anchored(@Nullable UUID companyId, @Nullable Long atOrBeforeSeq) {
        List<Object> params = new ArrayList<>(AuditEventStore.chainParams(companyId));
        String seq = "";
        if (atOrBeforeSeq != null) {
            seq = " AND seq <= ?";
            params.add(atOrBeforeSeq);
        }
        return jdbc.sql("SELECT number, issued_at, key_id, seq, hash FROM audit_checkpoint_chain WHERE "
                + AuditEventStore.chain(companyId) + seq + " ORDER BY number DESC LIMIT 1")
            .params(params)
            .query((rs, row) -> new AuditAnchors.Anchored(rs.getLong("number"), instant(rs, "issued_at"),
                    rs.getString("key_id"), rs.getLong("seq"), rs.getString("hash")))
            .optional();
    }

    /** Every head the database records for the chain, oldest first: the syslog sink's only record. */
    List<SinkIndex.Anchor> anchorsOf(@Nullable UUID companyId) {
        return jdbc.sql("SELECT number, seq, hash, issued_at, key_id FROM audit_checkpoint_chain WHERE "
                + AuditEventStore.chain(companyId) + " ORDER BY number")
            .params(AuditEventStore.chainParams(companyId))
            .query((rs, row) -> new SinkIndex.Anchor(rs.getLong("number"), rs.getLong("seq"), rs.getString("hash"),
                    instant(rs, "issued_at"), rs.getString("key_id")))
            .list();
    }

    /** The hashes of the chain's events at these positions; a position without an event is absent. */
    Map<Long, String> hashesAt(@Nullable UUID companyId, List<Long> seqs) {
        Map<Long, String> hashes = new HashMap<>();
        for (int from = 0; from < seqs.size(); from += 500) {
            List<Long> part = seqs.subList(from, Math.min(seqs.size(), from + 500));
            List<Object> params = new ArrayList<>(AuditEventStore.chainParams(companyId));
            params.addAll(part);
            jdbc.sql("SELECT seq, hash FROM audit_event WHERE " + AuditEventStore.chain(companyId) + " AND seq IN ("
                    + String.join(", ", java.util.Collections.nCopies(part.size(), "?")) + ")")
                .params(params)
                .query((rs, row) -> hashes.put(rs.getLong("seq"), rs.getString("hash")))
                .list();
        }
        return hashes;
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static SqlParameterValue uuid(@Nullable UUID value) {
        return new SqlParameterValue(Types.OTHER, value);
    }
}
