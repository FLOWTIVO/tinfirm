package com.flowtivo.tinfirm.run.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.TurnSource;

/**
 * SQL access to {@code run_repository} (V004): the workspace a run was assigned with, in order, with each
 * repository's git result once the run ended.
 */
@Repository
class RunRepositoryStore {

    private static final String COLUMNS = """
            run_id, position, repository_id, system_id, binding_id, name, dir, read_only, read_only_cause, pushed,
            head_sha, commits""";

    private final JdbcClient jdbc;

    RunRepositoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * One repository of a run.
     *
     * @param pushed whether the run pushed the branch, as its host reported; null until it ended, or without a result
     */
    record RepositoryRow(UUID runId, int position, @Nullable UUID repositoryId, @Nullable UUID systemId,
            @Nullable UUID bindingId, String name, String dir, boolean readOnly,
            TurnSource.@Nullable ReadOnlyCause readOnlyCause, @Nullable Boolean pushed, @Nullable String headSha,
            @Nullable Integer commits) {
    }

    void insert(UUID companyId, UUID runId, int position, TurnSource.Workspace workspace, String dir) {
        jdbc.sql("""
                INSERT INTO run_repository (run_id, company_id, position, repository_id, system_id, binding_id, name,
                                            dir, read_only, read_only_cause)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(runId, companyId, position, workspace.repositoryId(), workspace.systemId(), workspace.bindingId(),
                    workspace.name(), dir, workspace.readOnly(),
                    workspace.readOnlyCause() != null ? workspace.readOnlyCause().name() : null)
            .update();
    }

    /** The workspace of an earlier assignment of the run goes: each assignment sends its own. */
    void deleteOfRun(UUID runId) {
        jdbc.sql("DELETE FROM run_repository WHERE run_id = ?").param(runId).update();
    }

    List<RepositoryRow> ofRun(UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM run_repository WHERE run_id = ? ORDER BY position")
            .param(runId)
            .query(RunRepositoryStore::row)
            .list();
    }

    Map<UUID, List<RepositoryRow>> ofRuns(Collection<UUID> runIds) {
        Map<UUID, List<RepositoryRow>> rows = new HashMap<>();
        if (runIds.isEmpty()) {
            return rows;
        }
        jdbc.sql("SELECT " + COLUMNS + " FROM run_repository WHERE run_id IN (:ids) ORDER BY run_id, position")
            .param("ids", runIds)
            .query(RunRepositoryStore::row)
            .list()
            .forEach(row -> rows.computeIfAbsent(row.runId(), id -> new ArrayList<>()).add(row));
        return rows;
    }

    /** The latest push of the conversation's branch to the repository, by turn, of its runs but the one excepted. */
    Optional<RunReads.Push> lastPush(UUID conversationId, UUID repositoryId, @Nullable UUID exceptRunId) {
        return jdbc.sql("""
                SELECT rr.run_id, rr.head_sha
                  FROM run_repository rr
                  JOIN run r ON r.id = rr.run_id AND r.company_id = rr.company_id
                 WHERE r.conversation_id = ? AND rr.repository_id = ? AND rr.pushed AND rr.head_sha IS NOT NULL
                   AND rr.run_id <> ?
                 ORDER BY r.turn_number DESC, r.created_at DESC
                 LIMIT 1""")
            .params(conversationId, repositoryId, exceptRunId != null ? exceptRunId : new UUID(0, 0))
            .query((rs, row) -> new RunReads.Push(rs.getObject("run_id", UUID.class), rs.getString("head_sha")))
            .optional();
    }

    /** The git result of one repository at the run's end. */
    void finish(UUID runId, int position, @Nullable Boolean pushed, @Nullable String headSha,
            @Nullable Integer commits) {
        jdbc.sql("UPDATE run_repository SET pushed = ?, head_sha = ?, commits = ? WHERE run_id = ? AND position = ?")
            .params(pushed, headSha, commits, runId, position)
            .update();
    }

    /** A resumed run: its repositories have no git result again, as while it runs. */
    void reopen(UUID runId) {
        jdbc.sql("UPDATE run_repository SET pushed = NULL, head_sha = NULL, commits = NULL WHERE run_id = ?")
            .param(runId)
            .update();
    }

    private static RepositoryRow row(ResultSet rs, int row) throws SQLException {
        String cause = rs.getString("read_only_cause");
        return new RepositoryRow(rs.getObject("run_id", UUID.class), rs.getInt("position"),
                rs.getObject("repository_id", UUID.class), rs.getObject("system_id", UUID.class),
                rs.getObject("binding_id", UUID.class), rs.getString("name"), rs.getString("dir"),
                rs.getBoolean("read_only"), cause != null ? TurnSource.ReadOnlyCause.valueOf(cause) : null,
                rs.getObject("pushed", Boolean.class), rs.getString("head_sha"), rs.getObject("commits", Integer.class));
    }
}
