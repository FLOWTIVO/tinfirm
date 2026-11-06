package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.runner.RunnerMessages;

/** SQL access to {@code run_event}. Payloads are stored as the runner host sent them. */
@Repository
class RunEventStore {

    private final JdbcClient jdbc;

    RunEventStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    record EventRow(long seq, String type, Instant at, String payloadJson) {
    }

    /** Inserts an event; false when (run, seq) is already stored (a duplicate after a reconnect). */
    boolean insert(UUID companyId, UUID runId, long seq, String type, Instant at, String payloadJson) {
        return jdbc.sql("""
                INSERT INTO run_event (run_id, seq, company_id, type, at, payload)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (run_id, seq) DO NOTHING""")
            .params(runId, seq, companyId, type, RunStore.ts(at), payloadJson)
            .update() == 1;
    }

    boolean exists(UUID runId, long seq) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM run_event WHERE run_id = ? AND seq = ?)")
            .params(runId, seq)
            .query(Boolean.class)
            .single();
    }

    /** Drops the oldest LOG event of the run; false when it has none. */
    boolean deleteOldestLog(UUID runId) {
        return jdbc.sql("""
                DELETE FROM run_event
                 WHERE run_id = ? AND seq = (SELECT min(seq) FROM run_event WHERE run_id = ? AND type = 'LOG')""")
            .params(runId, runId)
            .update() == 1;
    }

    /**
     * The {@code resetsAt} of every RATE_LIMIT event of the run that has one: when the runtime said its usage limit
     * resets. A value that is no timestamp is skipped.
     */
    List<Instant> rateLimitResets(UUID runId) {
        List<Instant> resets = new ArrayList<>();
        jdbc.sql("""
                SELECT payload ->> 'resetsAt' AS resets_at
                  FROM run_event
                 WHERE run_id = ? AND type = 'RATE_LIMIT' AND payload ->> 'resetsAt' IS NOT NULL""")
            .param(runId)
            .query(rs -> {
                try {
                    resets.add(RunnerMessages.parseInstant(rs.getString("resets_at")));
                }
                catch (IllegalArgumentException ex) {
                    // An unreadable time adds nothing.
                }
            });
        return resets;
    }

    /** Events with {@code seq > afterSeq}, in sequence order. */
    List<EventRow> after(UUID runId, long afterSeq, int limit) {
        return after(runId, afterSeq, List.of(), limit);
    }

    /** Events with {@code seq > afterSeq} of one of {@code types} (any type when empty), in sequence order. */
    List<EventRow> after(UUID runId, long afterSeq, Collection<String> types, int limit) {
        List<Object> params = new ArrayList<>(List.of(runId, afterSeq));
        StringBuilder sql = new StringBuilder(
                "SELECT seq, type, at, payload::text AS payload FROM run_event WHERE run_id = ? AND seq > ?");
        if (!types.isEmpty()) {
            sql.append(" AND type = ANY(?)");
            params.add(types.toArray(String[]::new));
        }
        sql.append(" ORDER BY seq LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString())
            .params(params)
            .query((rs, row) -> new EventRow(rs.getLong("seq"), rs.getString("type"),
                    rs.getObject("at", OffsetDateTime.class).toInstant(), rs.getString("payload")))
            .list();
    }
}
