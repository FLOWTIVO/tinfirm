package com.flowtivo.tinfirm.audit.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL access to {@code audit_check} (V023), under row-level security: a company's checks in its context, the instance
 * chain's for instance admins.
 */
@Repository
class AuditCheckStore {

    private static final String COLUMNS = "id, company_id, checked_at, ok, from_seq, failure, first_invalid_seq,"
            + " head_seq, head_hash, events_checked, acknowledged_at, acknowledged_by, acknowledged_note, anchor_checkpoint";

    /** The checks kept per chain, beside the one the next check starts from. */
    static final int KEPT = 400;

    private final JdbcClient jdbc;

    AuditCheckStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One check of a chain.
     *
     * @param companyId null for the instance chain
     * @param failure          {@code BROKEN}, {@code SHORTER}, {@code HEAD_CHANGED}, {@code ANCHOR_MISMATCH}
     *                         (ADR-0111); null when it passed
     * @param anchorCheckpoint for {@code ANCHOR_MISMATCH}, the number of the checkpoint the chain disagrees with
     */
    record CheckRow(UUID id, @Nullable UUID companyId, Instant checkedAt, boolean ok, long fromSeq,
            @Nullable String failure, @Nullable Long firstInvalidSeq, long headSeq, String headHash,
            long eventsChecked, @Nullable Instant acknowledgedAt, @Nullable UUID acknowledgedBy,
            @Nullable String acknowledgedNote, @Nullable Long anchorCheckpoint) {

        /** Whether the next check starts from this one's head: it passed, or its failure was acknowledged. */
        boolean anchors() {
            return ok || acknowledgedAt != null;
        }
    }

    void insert(CheckRow row) {
        jdbc.sql("""
                INSERT INTO audit_check (id, company_id, checked_at, ok, from_seq, failure, first_invalid_seq,
                                         head_seq, head_hash, events_checked, anchor_checkpoint)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(row.id(), uuid(row.companyId()), ts(row.checkedAt()), row.ok(), row.fromSeq(), row.failure(),
                    row.firstInvalidSeq(), row.headSeq(), row.headHash(), row.eventsChecked(),
                    new SqlParameterValue(Types.BIGINT, row.anchorCheckpoint()))
            .update();
    }

    /** The check the next one starts from: the latest that passed or was acknowledged. */
    Optional<CheckRow> anchor(@Nullable UUID companyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_check WHERE " + chain(companyId)
                + " AND (ok OR acknowledged_at IS NOT NULL) ORDER BY checked_at DESC, id DESC LIMIT 1")
            .params(chainParams(companyId))
            .query(AuditCheckStore::map)
            .optional();
    }

    Optional<CheckRow> latest(@Nullable UUID companyId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_check WHERE " + chain(companyId)
                + " ORDER BY checked_at DESC, id DESC LIMIT 1")
            .params(chainParams(companyId))
            .query(AuditCheckStore::map)
            .optional();
    }

    Optional<CheckRow> find(@Nullable UUID companyId, UUID checkId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_check WHERE " + chain(companyId) + " AND id = ?")
            .params(append(chainParams(companyId), checkId))
            .query(AuditCheckStore::map)
            .optional();
    }

    /** Newest first, after the check with this key ({@code checkedAt}, {@code id}) when one is given. */
    List<CheckRow> newestFirst(@Nullable UUID companyId, @Nullable Instant beforeAt, @Nullable UUID beforeId,
            int limit) {
        List<Object> params = new ArrayList<>(chainParams(companyId));
        String after = "";
        if (beforeAt != null && beforeId != null) {
            after = " AND (checked_at, id) < (?, ?)";
            params.add(ts(beforeAt));
            params.add(beforeId);
        }
        params.add(limit);
        return jdbc.sql("SELECT " + COLUMNS + " FROM audit_check WHERE " + chain(companyId) + after
                + " ORDER BY checked_at DESC, id DESC LIMIT ?")
            .params(params)
            .query(AuditCheckStore::map)
            .list();
    }

    /** Keeps the chain's newest {@value #KEPT} checks and the one the next check starts from. */
    int prune(@Nullable UUID companyId) {
        List<Object> params = new ArrayList<>(chainParams(companyId));
        params.addAll(chainParams(companyId));
        params.add(KEPT);
        params.addAll(chainParams(companyId));
        return jdbc.sql("DELETE FROM audit_check WHERE " + chain(companyId)
                + " AND id NOT IN (SELECT id FROM audit_check WHERE " + chain(companyId)
                + " ORDER BY checked_at DESC, id DESC LIMIT ?)"
                + " AND id IS DISTINCT FROM (SELECT id FROM audit_check WHERE " + chain(companyId)
                + " AND (ok OR acknowledged_at IS NOT NULL) ORDER BY checked_at DESC, id DESC LIMIT 1)")
            .params(params)
            .update();
    }

    /**
     * A chain due for its daily check.
     *
     * @param companyId null for the instance chain
     */
    record Due(@Nullable UUID companyId) {
    }

    /** The chains not checked since the cutoff (V023's {@code audit_check_due}). */
    List<Due> due(Instant checkedBefore) {
        return jdbc.sql("SELECT company_id FROM audit_check_due(?)")
            .params(ts(checkedBefore))
            .query((rs, row) -> new Due(rs.getObject("company_id", UUID.class)))
            .list();
    }

    /** Takes the chain's check lock for the transaction; false when another worker checks it now. */
    boolean tryLock(@Nullable UUID companyId) {
        Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended(?, 0))")
            .params("audit_check:" + (companyId == null ? "instance" : companyId.toString()))
            .query(Boolean.class)
            .single();
        return Boolean.TRUE.equals(locked);
    }

    private static CheckRow map(ResultSet rs, int row) throws SQLException {
        long firstInvalid = rs.getLong("first_invalid_seq");
        Long firstInvalidSeq = rs.wasNull() ? null : firstInvalid;
        OffsetDateTime acknowledged = rs.getObject("acknowledged_at", OffsetDateTime.class);
        long checkpoint = rs.getLong("anchor_checkpoint");
        Long anchorCheckpoint = rs.wasNull() ? null : checkpoint;
        return new CheckRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("checked_at", OffsetDateTime.class).toInstant(), rs.getBoolean("ok"),
                rs.getLong("from_seq"), rs.getString("failure"), firstInvalidSeq, rs.getLong("head_seq"),
                rs.getString("head_hash"), rs.getLong("events_checked"),
                acknowledged != null ? acknowledged.toInstant() : null, rs.getObject("acknowledged_by", UUID.class),
                rs.getString("acknowledged_note"), anchorCheckpoint);
    }

    private static String chain(@Nullable UUID companyId) {
        return companyId == null ? "company_id IS NULL" : "company_id = ?";
    }

    private static List<Object> chainParams(@Nullable UUID companyId) {
        return companyId == null ? List.of() : List.of(companyId);
    }

    private static List<Object> append(List<Object> params, Object... last) {
        ArrayList<Object> all = new ArrayList<>(params);
        all.addAll(List.of(last));
        return all;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static SqlParameterValue uuid(@Nullable UUID value) {
        return new SqlParameterValue(Types.OTHER, value);
    }
}
