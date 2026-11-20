package com.flowtivo.tinfirm.run.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.shared.id.Ids;

/**
 * Durable commands to runner hosts ({@code run_command}). Two kinds, each PENDING until the run ends and sent again
 * whenever the host reconnects: CANCEL, one pending per run; and REVOKE_GIT (ADR-0116, V087), one per cause, each for
 * the run's assignment of its epoch and naming the repositories whose git token goes, with the reason and the message.
 */
@Repository
class RunCommandStore {

    private static final String CANCEL = "CANCEL";

    static final String REVOKE_GIT = "REVOKE_GIT";

    /**
     * A {@code REVOKE_GIT} command.
     *
     * @param lastSentAt when its frame was last written to the run's host's connection; null while it never was (a
     *                   host without {@code run.git.revoke}, one not connected since, or a send that failed)
     * @param delivery   {@code SENT} once its frame was written to the host's connection; {@code CONFIRMED} once the
     *                   host answered {@code run.git.revoked} for it; {@code ON_RECONNECT} while it was not written;
     *                   {@code UNSUPPORTED} while the run's host does not list {@code run.git.revoke}
     * @param confirmedAt when the host first confirmed it; null unless {@code CONFIRMED}
     */
    record GitRevocation(UUID id, UUID runId, long epoch, List<UUID> componentIds, String reason, String message,
            Instant createdAt, @Nullable Instant lastSentAt, boolean pending, String delivery,
            @Nullable Instant confirmedAt) {

        GitRevocation {
            componentIds = List.copyOf(componentIds);
        }
    }

    private final JdbcClient jdbc;

    RunCommandStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Records a pending cancel; false when one is already pending. */
    boolean requestCancel(UUID companyId, UUID runId, @Nullable UUID createdBy, Instant now) {
        return jdbc.sql("""
                INSERT INTO run_command (id, company_id, run_id, type, status, created_by, created_at, last_sent_at)
                VALUES (?, ?, ?, ?, 'PENDING', ?, ?, ?)
                ON CONFLICT (run_id, type) WHERE status = 'PENDING' AND type = 'CANCEL' DO NOTHING""")
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
        jdbc.sql("UPDATE run_command SET last_sent_at = ? WHERE run_id = ? AND status = 'PENDING' AND type = 'CANCEL'")
            .params(RunStore.ts(now), runId)
            .update();
    }

    /**
     * Records a revocation of the run's git tokens for the assignment of {@code epoch}. A run that is LOST (within
     * the resume window) gets it as its other commands are, completed at the run's end, so that its resumption opens it
     * again ({@link #reopen}).
     *
     * @param completedAt null for a pending command; the lost run's end otherwise
     * @param delivery    {@code ON_RECONNECT}, {@code UNSUPPORTED}, or {@code SENT} for a frame in the answer to a
     *                    {@code hello} (then {@code last_sent_at} is now)
     */
    UUID insertRevocation(UUID companyId, UUID runId, long epoch, List<UUID> componentIds, String reason,
            String message, @Nullable UUID createdBy, Instant now, @Nullable Instant completedAt, String delivery) {
        UUID id = Ids.newId();
        jdbc.sql("""
                INSERT INTO run_command (id, company_id, run_id, type, status, created_by, created_at, completed_at,
                                         last_sent_at, epoch, component_ids, reason, message, delivery)
                VALUES (?, ?, ?, 'REVOKE_GIT', ?, ?, ?, ?, ?, ?, CAST(? AS uuid[]), ?, ?, ?)""")
            .params(id, companyId, runId, completedAt == null ? "PENDING" : "DONE", createdBy, RunStore.ts(now),
                    completedAt != null ? RunStore.ts(completedAt) : null,
                    SENT.equals(delivery) ? RunStore.ts(now) : null, epoch, uuidArray(componentIds), reason, message,
                    delivery)
            .update();
        return id;
    }

    /** Every revocation of the run's assignment of {@code epoch}, pending or done, oldest first. */
    List<GitRevocation> revocationsOf(UUID runId, long epoch) {
        return jdbc.sql("SELECT " + REVOCATION_COLUMNS + """
                 FROM run_command
                WHERE type = 'REVOKE_GIT' AND run_id = ? AND epoch = ?
                ORDER BY created_at, id""")
            .params(runId, epoch)
            .query(RunCommandStore::revocation)
            .list();
    }

    /** The repositories a revocation of the run's assignment of {@code epoch} named, pending or done. */
    Set<UUID> revokedComponents(UUID runId, long epoch) {
        Set<UUID> revoked = new HashSet<>();
        jdbc.sql("SELECT component_ids FROM run_command WHERE run_id = ? AND type = 'REVOKE_GIT' AND epoch = ?")
            .params(runId, epoch)
            .query((rs, row) -> uuids(rs.getArray("component_ids")))
            .list()
            .forEach(revoked::addAll);
        return revoked;
    }

    /** The pending revocations of the runs, oldest first. */
    List<GitRevocation> pendingRevocations(Collection<UUID> runIds) {
        if (runIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + REVOCATION_COLUMNS + """
                 FROM run_command
                WHERE type = 'REVOKE_GIT' AND status = 'PENDING' AND run_id IN (:ids)
                ORDER BY created_at, id""")
            .param("ids", runIds)
            .query(RunCommandStore::revocation)
            .list();
    }

    /** Every revocation of the runs, oldest first, by run. */
    Map<UUID, List<GitRevocation>> revocationsOfRuns(Collection<UUID> runIds) {
        if (runIds.isEmpty()) {
            return Map.of();
        }
        return jdbc.sql("SELECT " + REVOCATION_COLUMNS + """
                 FROM run_command
                WHERE type = 'REVOKE_GIT' AND run_id IN (:ids)
                ORDER BY created_at, id""")
            .param("ids", runIds)
            .query(RunCommandStore::revocation)
            .list()
            .stream()
            .collect(Collectors.groupingBy(GitRevocation::runId, HashMap::new, Collectors.toList()));
    }

    /** The revocation's frame was written to the run's host's connection; a confirmed one stays confirmed. */
    void markRevocationSent(UUID commandId, Instant now) {
        jdbc.sql("""
                UPDATE run_command SET last_sent_at = ?,
                       delivery = CASE WHEN delivery = 'CONFIRMED' THEN delivery ELSE 'SENT' END
                 WHERE id = ?""")
            .params(RunStore.ts(now), commandId)
            .update();
    }

    /**
     * The frame of a revocation was written to the host's connection in an answer: the revocation of the run's
     * assignment of {@code epoch} that names exactly these repositories (no two of one assignment name the same one).
     * A confirmed one stays confirmed.
     */
    void markRevocationWritten(UUID runId, long epoch, List<UUID> componentIds, Instant now) {
        jdbc.sql("""
                UPDATE run_command SET last_sent_at = ?,
                       delivery = CASE WHEN delivery = 'CONFIRMED' THEN delivery ELSE 'SENT' END
                 WHERE run_id = ? AND type = 'REVOKE_GIT' AND epoch = ? AND component_ids = CAST(? AS uuid[])""")
            .params(RunStore.ts(now), runId, epoch, uuidArray(componentIds))
            .update();
    }

    /**
     * The host that reports the run's assignment of {@code epoch} cannot take a revocation: those it was never sent
     * cannot reach it.
     */
    void markRevocationsUnsupported(UUID runId, long epoch) {
        jdbc.sql("""
                UPDATE run_command SET delivery = 'UNSUPPORTED'
                 WHERE run_id = ? AND type = 'REVOKE_GIT' AND epoch = ? AND delivery NOT IN ('SENT', 'CONFIRMED')""")
            .params(runId, epoch)
            .update();
    }

    /** The run's host reconnected without {@code run.git.revoke}: a revocation it was never sent cannot reach it. */
    void markRevocationUnsupported(UUID commandId) {
        jdbc.sql("""
                UPDATE run_command SET delivery = 'UNSUPPORTED' WHERE id = ? AND delivery NOT IN ('SENT', 'CONFIRMED')""")
            .param(commandId)
            .update();
    }

    /** A revocation the host confirmed for the first time ({@link #confirmRevocations}). */
    record Confirmed(UUID id, List<UUID> componentIds, String reason) {

        Confirmed {
            componentIds = List.copyOf(componentIds);
        }
    }

    /**
     * The run's host applied the revocations of the assignment of {@code epoch} that name these repositories
     * ({@code run.git.revoked}): those not confirmed yet are {@code CONFIRMED} from now. A revocation is matched by any
     * repository it names, since no two of one assignment name the same one, and the host names the repositories of
     * the frame that its run has.
     *
     * @return the revocations confirmed now; none when each was confirmed before
     */
    List<Confirmed> confirmRevocations(UUID runId, long epoch, Collection<UUID> componentIds, Instant now) {
        return jdbc.sql("""
                UPDATE run_command SET delivery = 'CONFIRMED', confirmed_at = ?
                 WHERE run_id = ? AND type = 'REVOKE_GIT' AND epoch = ? AND component_ids && CAST(? AS uuid[])
                   AND delivery <> 'CONFIRMED'
                RETURNING id, component_ids, reason""")
            .params(RunStore.ts(now), runId, epoch, uuidArray(componentIds))
            .query((rs, row) -> new Confirmed(rs.getObject("id", UUID.class), uuids(rs.getArray("component_ids")),
                    rs.getString("reason")))
            .list();
    }

    /**
     * The revocations of the run's assignment of {@code epoch} whose frame was never written to a connection (neither
     * {@code SENT} nor {@code CONFIRMED}), pending or done, oldest first.
     */
    List<GitRevocation> unwrittenRevocations(UUID runId, long epoch) {
        return jdbc.sql("SELECT " + REVOCATION_COLUMNS + """
                 FROM run_command
                WHERE type = 'REVOKE_GIT' AND run_id = ? AND epoch = ? AND delivery NOT IN ('SENT', 'CONFIRMED')
                ORDER BY created_at, id""")
            .params(runId, epoch)
            .query(RunCommandStore::revocation)
            .list();
    }

    /**
     * Holds back, until this transaction ends, the commit of a revocation of a run on the host while another
     * transaction ends the host's {@code hello} answer ({@link #holdRevocationsOf}): shared, so revocations do not wait
     * for each other.
     */
    void lockRevocationsOf(UUID runnerHostId) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))")
            .param(REVOCATIONS_LOCK + runnerHostId)
            .query()
            .singleRow();
    }

    /**
     * Holds back, until this transaction ends, every revocation of a run on these hosts that has not taken
     * {@link #lockRevocationsOf} yet, and waits for those that have to commit. The hosts in the order of their ids.
     */
    void holdRevocationsOf(Collection<UUID> runnerHostIds) {
        runnerHostIds.stream().sorted().forEach(id -> jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
            .param(REVOCATIONS_LOCK + id)
            .query()
            .singleRow());
    }

    private static final String REVOCATIONS_LOCK = "run-git-revocations:";

    /** {@code run_command.delivery} of a revocation whose frame was written to the host's connection. */
    static final String SENT = "SENT";

    /** {@code run_command.delivery} of a revocation whose host confirmed it ({@code run.git.revoked}). */
    static final String CONFIRMED = "CONFIRMED";

    private static final String REVOCATION_COLUMNS = """
            id, run_id, epoch, component_ids, reason, message, created_at, last_sent_at, status, delivery,
            confirmed_at""";

    private static GitRevocation revocation(ResultSet rs, int row) throws SQLException {
        Timestamp sent = rs.getTimestamp("last_sent_at");
        Timestamp confirmed = rs.getTimestamp("confirmed_at");
        return new GitRevocation(rs.getObject("id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getLong("epoch"), uuids(rs.getArray("component_ids")), rs.getString("reason"),
                rs.getString("message"), rs.getTimestamp("created_at").toInstant(),
                sent != null ? sent.toInstant() : null, "PENDING".equals(rs.getString("status")),
                rs.getString("delivery"), confirmed != null ? confirmed.toInstant() : null);
    }

    private static List<UUID> uuids(@Nullable Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        List<UUID> ids = new ArrayList<>();
        for (Object element : (Object[]) array.getArray()) {
            ids.add(element instanceof UUID id ? id : UUID.fromString(element.toString()));
        }
        return ids;
    }

    /** A PostgreSQL array literal of UUIDs, which never need quoting. */
    static String uuidArray(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
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
