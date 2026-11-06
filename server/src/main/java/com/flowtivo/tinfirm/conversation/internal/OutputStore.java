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

/**
 * SQL access to {@code output}: what a conversation produced (V005). Row-level security limits every statement to the
 * company of the tenant context. An output is never deleted; a change request's state changes in place.
 */
@Repository
class OutputStore {

    private static final String COLUMNS = """
            id, company_id, conversation_id, run_id, kind, title, url, attachment_id, repository_id, external_number,
            change_request_state, request_id, created_at, purged_at""";

    private final JdbcClient jdbc;

    private final ConversationSignals signals;

    OutputStore(JdbcClient jdbc, ConversationSignals signals) {
        this.jdbc = jdbc;
        this.signals = signals;
    }

    /**
     * An output as stored.
     *
     * @param title    empty once purged
     * @param purgedAt when retention or an erasure removed its title; its kind and link stay
     */
    record OutputRow(UUID id, UUID companyId, UUID conversationId, @Nullable UUID runId,
            ConversationOutputs.Kind kind, String title, @Nullable String url, @Nullable UUID attachmentId,
            @Nullable UUID repositoryId, @Nullable String externalNumber,
            ConversationOutputs.@Nullable ChangeRequestState changeRequestState, @Nullable UUID requestId,
            Instant createdAt, @Nullable Instant purgedAt) {

        OutputRow(UUID id, UUID companyId, UUID conversationId, @Nullable UUID runId, ConversationOutputs.Kind kind,
                String title, @Nullable String url, @Nullable UUID attachmentId, @Nullable UUID repositoryId,
                @Nullable String externalNumber, ConversationOutputs.@Nullable ChangeRequestState changeRequestState,
                @Nullable UUID requestId, Instant createdAt) {
            this(id, companyId, conversationId, runId, kind, title, url, attachmentId, repositoryId, externalNumber,
                    changeRequestState, requestId, createdAt, null);
        }
    }

    /** Stores the output unless one of the conversation has its request id, or one is its attachment's already. */
    boolean insert(OutputRow row) {
        boolean inserted = jdbc.sql("""
                INSERT INTO output (id, company_id, conversation_id, run_id, kind, title, url, attachment_id,
                                    repository_id, external_number, change_request_state, request_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING""")
            .params(row.id(), row.companyId(), row.conversationId(), row.runId(), row.kind().name(), row.title(),
                    row.url(), row.attachmentId(), row.repositoryId(), row.externalNumber(),
                    row.changeRequestState() != null ? row.changeRequestState().name() : null, row.requestId(),
                    AttachmentStore.ts(row.createdAt()))
            .update() == 1;
        if (inserted) {
            signals.changed(row.conversationId(), ConversationSignals.Part.OUTPUTS);
        }
        return inserted;
    }

    Optional<OutputRow> find(UUID outputId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM output WHERE id = ?")
            .param(outputId)
            .query(OutputStore::row)
            .optional();
    }

    /** The output a request of the conversation saved, if any ({@code save_artifact} with a link). */
    Optional<OutputRow> ofRequest(UUID conversationId, UUID requestId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM output WHERE conversation_id = ? AND request_id = ?")
            .params(conversationId, requestId)
            .query(OutputStore::row)
            .optional();
    }

    /** The output whose document the file is, if any. */
    Optional<OutputRow> ofAttachment(UUID attachmentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM output WHERE attachment_id = ?")
            .param(attachmentId)
            .query(OutputStore::row)
            .optional();
    }

    /** The conversation's outputs, oldest first. */
    List<OutputRow> ofConversation(UUID conversationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM output WHERE conversation_id = ? ORDER BY created_at, id")
            .param(conversationId)
            .query(OutputStore::row)
            .list();
    }

    /** A change request's state as last read. */
    void changeRequestState(UUID outputId, ConversationOutputs.ChangeRequestState state) {
        jdbc.sql("UPDATE output SET change_request_state = ? WHERE id = ? AND kind = 'CHANGE_REQUEST'")
            .params(state.name(), outputId)
            .update();
    }

    private static OutputRow row(ResultSet rs, int row) throws SQLException {
        String state = rs.getString("change_request_state");
        String title = rs.getString("title");
        OffsetDateTime purgedAt = rs.getObject("purged_at", OffsetDateTime.class);
        return new OutputRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getObject("run_id", UUID.class),
                ConversationOutputs.Kind.valueOf(rs.getString("kind")), title != null ? title : "", rs.getString("url"),
                rs.getObject("attachment_id", UUID.class), rs.getObject("repository_id", UUID.class),
                rs.getString("external_number"),
                state != null ? ConversationOutputs.ChangeRequestState.valueOf(state) : null,
                rs.getObject("request_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                purgedAt != null ? purgedAt.toInstant() : null);
    }
}
