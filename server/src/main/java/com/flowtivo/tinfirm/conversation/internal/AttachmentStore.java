package com.flowtivo.tinfirm.conversation.internal;

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
import org.springframework.stereotype.Repository;

/**
 * SQL access to {@code attachment}: a conversation's files (V005, V008). Lists never select the content. Row-level
 * security limits every statement to the company of the tenant context.
 */
@Repository
class AttachmentStore {

    private static final String COLUMNS = """
            a.id, a.company_id, a.conversation_id, a.file_name, a.content_type, a.size_bytes, a.sha256, a.note,
            a.uploaded_by_user_id, a.uploaded_by_agent_id, a.run_id, a.created_at,
            (SELECT o.id FROM output o WHERE o.attachment_id = a.id) AS output_id, a.content_purged_at""";

    private final JdbcClient jdbc;

    private final ConversationSignals signals;

    AttachmentStore(JdbcClient jdbc, ConversationSignals signals) {
        this.jdbc = jdbc;
        this.signals = signals;
    }

    /**
     * A file of a conversation, without its content.
     *
     * @param uploadedByUserId  the person who added it
     * @param uploadedByAgentId the agent that saved it, in the run {@code runId}
     * @param outputId          the output whose document it is
     * @param contentPurgedAt   when retention or an erasure removed its content (and note); its name, size and hash
     *                          stay
     */
    record AttachmentRow(UUID id, UUID companyId, UUID conversationId, String fileName, String contentType,
            long sizeBytes, String sha256, @Nullable String note, @Nullable UUID uploadedByUserId,
            @Nullable UUID uploadedByAgentId, @Nullable UUID runId, Instant createdAt, @Nullable UUID outputId,
            @Nullable Instant contentPurgedAt) {

        AttachmentRow(UUID id, UUID companyId, UUID conversationId, String fileName, String contentType,
                long sizeBytes, String sha256, @Nullable String note, @Nullable UUID uploadedByUserId,
                @Nullable UUID uploadedByAgentId, @Nullable UUID runId, Instant createdAt, @Nullable UUID outputId) {
            this(id, companyId, conversationId, fileName, contentType, sizeBytes, sha256, note, uploadedByUserId,
                    uploadedByAgentId, runId, createdAt, outputId, null);
        }
    }

    /** How many files a conversation holds, and how many bytes in all. */
    record Totals(long count, long bytes) {
    }

    /** The conversation's state, read without a lock: what an upload checks before its scan, and again under it. */
    Optional<String> state(UUID conversationId) {
        return jdbc.sql("SELECT state FROM conversation WHERE id = ?")
            .param(conversationId)
            .query(String.class)
            .optional();
    }

    /**
     * Locks the conversation's row against concurrent uploads, so that its limits hold, and answers its state
     * ({@code OPEN}, {@code ENDED}); empty when the tenant context does not see it.
     */
    Optional<String> lockConversation(UUID conversationId) {
        return jdbc.sql("SELECT state FROM conversation WHERE id = ? FOR NO KEY UPDATE")
            .param(conversationId)
            .query(String.class)
            .optional();
    }

    void insert(AttachmentRow row, byte[] content) {
        jdbc.sql("""
                INSERT INTO attachment (id, company_id, conversation_id, file_name, content_type, size_bytes, sha256,
                                        content, note, uploaded_by_user_id, uploaded_by_agent_id, run_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(row.id(), row.companyId(), row.conversationId(), row.fileName(), row.contentType(),
                    row.sizeBytes(), row.sha256(), content, row.note(), row.uploadedByUserId(),
                    row.uploadedByAgentId(), row.runId(), ts(row.createdAt()))
            .update();
        signals.changed(row.conversationId(), ConversationSignals.Part.ATTACHMENTS);
    }

    /** The conversation's files, oldest first. */
    List<AttachmentRow> list(UUID conversationId) {
        return jdbc.sql("SELECT " + COLUMNS
                + " FROM attachment a WHERE a.conversation_id = ? ORDER BY a.created_at, a.id")
            .param(conversationId)
            .query(AttachmentStore::row)
            .list();
    }

    Optional<AttachmentRow> find(UUID conversationId, UUID attachmentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM attachment a WHERE a.conversation_id = ? AND a.id = ?")
            .params(conversationId, attachmentId)
            .query(AttachmentStore::row)
            .optional();
    }

    /** The file's content; empty when there is none such, or its content was purged. */
    Optional<byte[]> content(UUID conversationId, UUID attachmentId) {
        return jdbc.sql("SELECT content FROM attachment WHERE conversation_id = ? AND id = ? AND content IS NOT NULL")
            .params(conversationId, attachmentId)
            .query((rs, row) -> rs.getBytes("content"))
            .optional();
    }

    boolean exists(UUID conversationId, String fileName) {
        return jdbc.sql("SELECT count(*) FROM attachment WHERE conversation_id = ? AND file_name = ?")
            .params(conversationId, fileName)
            .query(Long.class)
            .single() > 0;
    }

    Totals totals(UUID conversationId) {
        return jdbc.sql("""
                SELECT count(*) AS n, coalesce(sum(size_bytes), 0) AS bytes FROM attachment WHERE conversation_id = ?""")
            .param(conversationId)
            .query((rs, row) -> new Totals(rs.getLong("n"), rs.getLong("bytes")))
            .single();
    }

    /**
     * The files among {@code ids} of conversations of the person (any of them, ended ones included), in the order of
     * their creation.
     */
    List<AttachmentRow> ofPerson(UUID personPrincipalId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM attachment a JOIN conversation c ON c.id = a.conversation_id
                WHERE c.person_principal_id = :person AND a.id IN (:ids)
                ORDER BY a.created_at, a.id""")
            .param("person", personPrincipalId)
            .param("ids", ids)
            .query(AttachmentStore::row)
            .list();
    }

    /** A copy of a file into another conversation, as a file its person added, with the same content. */
    void copy(UUID attachmentId, AttachmentRow copy) {
        jdbc.sql("""
                INSERT INTO attachment (id, company_id, conversation_id, file_name, content_type, size_bytes, sha256,
                                        content, note, uploaded_by_user_id, created_at)
                SELECT ?, ?, ?, file_name, content_type, size_bytes, sha256, content, note, ?, ?
                  FROM attachment WHERE id = ?""")
            .params(copy.id(), copy.companyId(), copy.conversationId(), copy.uploadedByUserId(),
                    ts(copy.createdAt()), attachmentId)
            .update();
        signals.changed(copy.conversationId(), ConversationSignals.Part.ATTACHMENTS);
    }

    /** Deletes a file that is no output's document; false when there is none such. */
    boolean delete(UUID conversationId, UUID attachmentId) {
        boolean deleted = jdbc.sql("""
                DELETE FROM attachment a
                 WHERE a.conversation_id = ? AND a.id = ?
                   AND NOT EXISTS (SELECT 1 FROM output o WHERE o.attachment_id = a.id)""")
            .params(conversationId, attachmentId)
            .update() == 1;
        if (deleted) {
            signals.changed(conversationId, ConversationSignals.Part.ATTACHMENTS);
        }
        return deleted;
    }

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    private static AttachmentRow row(ResultSet rs, int row) throws SQLException {
        return new AttachmentRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getString("file_name"), rs.getString("content_type"),
                rs.getLong("size_bytes"), rs.getString("sha256"), rs.getString("note"),
                rs.getObject("uploaded_by_user_id", UUID.class), rs.getObject("uploaded_by_agent_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("output_id", UUID.class), instant(rs, "content_purged_at"));
    }
}
