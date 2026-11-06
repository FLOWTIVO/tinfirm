package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.shared.id.Ids;

/**
 * Durable commands to runner hosts ({@code run_command}). One kind, CANCEL: it stays PENDING until the run ends and is
 * sent again whenever the host reconnects.
 */
@Repository
class RunCommandStore {

    private static final String CANCEL = "CANCEL";

    private final JdbcClient jdbc;

    RunCommandStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records a pending cancel; false when one is already pending. */
    boolean requestCancel(UUID companyId, UUID runId, @Nullable UUID createdBy, Instant now) {
        return jdbc.sql("""
                INSERT INTO run_command (id, company_id, run_id, type, status, created_by, created_at, last_sent_at)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)
                ON CONFLICT (run_id, type) WHERE status = 'PENDING' DO NOTHING""")
            .params(Ids.newId(), companyId, runId, CANCEL, createdBy, RunStore.ts(now), RunStore.ts(now))
            .update() == 1;
    }

    /** Runs on the host with a pending cancel. */
    List<UUID> pendingCancels(List<UUID> runIds) {
        if (runIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT run_id FROM run_command WHERE status = 'PENDING' AND type = 'CANCEL' AND run_id IN (:ids)""")
            .param("ids", runIds)
            .query(UUID.class)
            .list();
    }

    void markSent(UUID runId, Instant now) {
        jdbc.sql("UPDATE run_command SET last_sent_at = ? WHERE run_id = ? AND status = 'PENDING'")
            .params(RunStore.ts(now), runId)
            .update();
    }

    /**
     * A resumed run: the commands that were still pending when the run was marked LOST, and that its end completed,
     * are pending again; a cancel a person asked for stays in force.
     */
    void reopen(UUID runId, Instant completedAt) {
        jdbc.sql("""
                UPDATE run_command SET status = 'PENDING', completed_at = NULL
                 WHERE run_id = ? AND status = 'DONE' AND completed_at = ?""")
            .params(runId, RunStore.ts(completedAt))
            .update();
    }

    /** The run has ended: its pending commands are done. */
    void completeAll(UUID runId, Instant now) {
        jdbc.sql("UPDATE run_command SET status = 'DONE', completed_at = ? WHERE run_id = ? AND status = 'PENDING'")
            .params(RunStore.ts(now), runId)
            .update();
    }
}
