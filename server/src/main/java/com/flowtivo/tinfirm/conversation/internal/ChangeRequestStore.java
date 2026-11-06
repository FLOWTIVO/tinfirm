package com.flowtivo.tinfirm.conversation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.system.ChangeRequests;

/**
 * SQL access to {@code run_change_request} (V008, V038): the change requests the server opened, keyed by their
 * {@code CHANGE_REQUEST} output, with the state and head commit as last read from the code host (ADR-0022). A
 * conversation has at most one per repository that is not MERGED, its latest there: the one a push goes to.
 * Row-level security limits every statement to the company of the tenant context; the cross-company lookup of the
 * states to read is the {@code SECURITY DEFINER} function of V008.
 */
@Repository
class ChangeRequestStore {

    private static final String COLUMNS = """
            output_id, company_id, conversation_id, run_id, repository_id, binding_id, number, url, state,
            state_checked_at, watched, created_at, head_sha""";

    private final JdbcClient jdbc;

    ChangeRequestStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A change request of a conversation in one repository.
     *
     * @param runId          the turn whose push it was opened for
     * @param bindingId      the conversation's binding on the code host, which it is opened and read with
     * @param number         the pull request's number, or the project-scoped merge request iid
     * @param state          as last read, or as the code host answered when it was opened; null before the first read
     * @param stateCheckedAt when the state was last read; null when it is due now
     * @param watched        whether the state is still read
     * @param headSha        its head commit as last read; for a MERGED one, the commit it was merged at; null before a
     *                       read that had one
     */
    record ChangeRequestRow(UUID outputId, UUID companyId, UUID conversationId, UUID runId, UUID repositoryId,
            UUID bindingId, int number, String url, ChangeRequests.@Nullable State state,
            @Nullable Instant stateCheckedAt, boolean watched, Instant createdAt, @Nullable String headSha) {

        boolean merged() {
            return state == ChangeRequests.State.MERGED;
        }
    }

    /** A change request whose state is due, found by the cross-company lookup. */
    record DueRef(UUID outputId, UUID companyId, UUID repositoryId) {
    }

    void insert(ChangeRequestRow row) {
        jdbc.sql("""
                INSERT INTO run_change_request (output_id, company_id, conversation_id, run_id, repository_id,
                                                binding_id, number, url, state, state_checked_at, watched, created_at,
                                                head_sha)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(row.outputId(), row.companyId(), row.conversationId(), row.runId(), row.repositoryId(),
                    row.bindingId(), row.number(), row.url(), row.state() != null ? row.state().name() : null,
                    row.stateCheckedAt() != null ? AttachmentStore.ts(row.stateCheckedAt()) : null, row.watched(),
                    AttachmentStore.ts(row.createdAt()), row.headSha())
            .update();
    }

    Optional<ChangeRequestRow> find(UUID outputId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM run_change_request WHERE output_id = ?")
            .param(outputId)
            .query(ChangeRequestStore::row)
            .optional();
    }

    /** The conversation's change requests, oldest first. */
    List<ChangeRequestRow> ofConversation(UUID conversationId) {
        return jdbc.sql("SELECT " + COLUMNS
                + " FROM run_change_request WHERE conversation_id = ? ORDER BY created_at, output_id")
            .param(conversationId)
            .query(ChangeRequestStore::row)
            .list();
    }

    /**
     * The conversation's latest change request in the repository, if one was opened: the one a push goes to unless it
     * is MERGED (the others are all MERGED).
     */
    Optional<ChangeRequestRow> latest(UUID conversationId, UUID repositoryId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM run_change_request WHERE conversation_id = ? AND repository_id = ?"
                + " ORDER BY created_at DESC, output_id DESC LIMIT 1")
            .params(conversationId, repositoryId)
            .query(ChangeRequestStore::row)
            .optional();
    }

    /**
     * A later turn pushed to the change request: its state is due to be read again, while it is watched. One no
     * longer watched stays so: MERGED, its conversation ended, or its binding gone.
     */
    void markDue(UUID conversationId, UUID repositoryId) {
        jdbc.sql("""
                UPDATE run_change_request SET state_checked_at = NULL
                 WHERE conversation_id = ? AND repository_id = ? AND watched""")
            .params(conversationId, repositoryId)
            .update();
    }

    /** Stamps the read time of a watched change request whose state is due, so that no other worker reads it. */
    boolean claim(UUID outputId, Instant checkedBefore, Instant now) {
        return jdbc.sql("""
                UPDATE run_change_request SET state_checked_at = ?
                 WHERE output_id = ? AND watched AND (state_checked_at IS NULL OR state_checked_at < ?)""")
            .params(AttachmentStore.ts(now), outputId, AttachmentStore.ts(checkedBefore))
            .update() == 1;
    }

    /**
     * A MERGED change request whose head commit was never read (opened before V038) is read once more, for the commit
     * it was merged at.
     */
    void rearm(UUID outputId) {
        jdbc.sql("""
                UPDATE run_change_request SET watched = true, state_checked_at = NULL
                 WHERE output_id = ? AND state = 'MERGED' AND head_sha IS NULL""")
            .param(outputId)
            .update();
    }

    /**
     * Records what the code host reported, the head commit when it named one; a MERGED change request is final and no
     * longer watched.
     */
    void recordState(UUID outputId, ChangeRequests.Read read) {
        jdbc.sql("""
                UPDATE run_change_request SET state = ?, head_sha = coalesce(?, head_sha),
                                              watched = watched AND ? <> 'MERGED'
                 WHERE output_id = ?""")
            .params(read.state().name(), read.headSha(), read.state().name(), outputId)
            .update();
    }

    /** The conversation ended: its change requests are no longer watched. */
    int unwatchConversation(UUID conversationId) {
        return jdbc.sql("UPDATE run_change_request SET watched = false WHERE conversation_id = ? AND watched")
            .param(conversationId)
            .update();
    }

    /** The binding is gone for good: the change requests read with it are no longer watched. */
    int unwatchBinding(UUID bindingId) {
        return jdbc.sql("UPDATE run_change_request SET watched = false WHERE binding_id = ? AND watched")
            .param(bindingId)
            .update();
    }

    /** The turn an approval of a change request was raised for ({@code decision.run_id}), if it names one. */
    Optional<UUID> runOfDecision(UUID decisionId) {
        return jdbc.sql("SELECT run_id FROM decision WHERE id = ? AND run_id IS NOT NULL")
            .param(decisionId)
            .query(UUID.class)
            .optional();
    }

    /** Whether a question the run asked is still open: the turn ended on its own questions (ADR-0036). */
    boolean hasOpenQuestion(UUID runId) {
        return jdbc.sql("SELECT count(*) FROM decision WHERE run_id = ? AND kind = 'QUESTION' AND status = 'OPEN'")
            .param(runId)
            .query(Long.class)
            .single() > 0;
    }

    // ---- The cross-company lookup (SECURITY DEFINER, ids only) -------------------------------------------------------

    /** Watched change requests whose state is due, least recently read first. */
    List<DueRef> stateDue(Instant checkedBefore, int limit) {
        return jdbc.sql("SELECT output_id, company_id, repository_id FROM run_change_request_state_due(?, ?)")
            .params(AttachmentStore.ts(checkedBefore), limit)
            .query((rs, row) -> new DueRef(rs.getObject("output_id", UUID.class),
                    rs.getObject("company_id", UUID.class), rs.getObject("repository_id", UUID.class)))
            .list();
    }

    private static ChangeRequestRow row(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("state");
        return new ChangeRequestRow(rs.getObject("output_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("repository_id", UUID.class), rs.getObject("binding_id", UUID.class),
                rs.getInt("number"), rs.getString("url"), state != null ? ChangeRequests.State.valueOf(state) : null,
                AttachmentStore.instant(rs, "state_checked_at"), rs.getBoolean("watched"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(), rs.getString("head_sha"));
    }
}
