package com.flowtivo.tinfirm.conversation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The files people's clients hold for a start (ADR-0084; {@code V057__staged_files.sql}): in the caller's transaction
 * and tenant context, but the sweep, which deletes the expired files of every company once a minute on a worker
 * replica, in each company's context in turn.
 */
@Repository
class StagedFileStore {

    private static final Logger log = LoggerFactory.getLogger(StagedFileStore.class);

    /** The most companies one pass of the sweep visits. */
    static final int BATCH = 500;

    private static final String COLUMNS = "id, company_id, principal_id, user_id, file_name, content_type, size_bytes,"
            + " sha256, created_at, expires_at";

    /** A held file, without its content. */
    record StagedRow(UUID id, UUID companyId, UUID principalId, UUID userId, String fileName, String contentType,
            long sizeBytes, String sha256, Instant createdAt, Instant expiresAt) {
    }

    /** How many files a person holds, and how many bytes in all. */
    record Totals(long count, long bytes) {
    }

    private final JdbcClient jdbc;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final boolean worker;

    private final ConversationSignals signals;

    StagedFileStore(JdbcClient jdbc, SystemTransactions transactions, TimeSource time, TinfirmProperties tinfirm,
            ConversationSignals signals) {
        this.jdbc = jdbc;
        this.signals = signals;
        this.transactions = transactions;
        this.time = time;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
    }

    void insert(StagedRow row, byte[] content) {
        jdbc.sql("""
                INSERT INTO staged_file (id, company_id, principal_id, user_id, file_name, content_type, size_bytes,
                                         sha256, content, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(row.id(), row.companyId(), row.principalId(), row.userId(), row.fileName(), row.contentType(),
                    row.sizeBytes(), row.sha256(), content, ts(row.createdAt()), ts(row.expiresAt()))
            .update();
    }

    /**
     * Serialises a person's holds until the transaction ends, on every replica: a transaction-scoped advisory lock on
     * their principal ({@code staged_file:{principalId}}), taken before the count and before any row lock of the hold
     * (CONVENTIONS §10), so that two holds one short of the limit never both count one short of it.
     */
    void lockHolds(UUID principalId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
            .params("staged_file:" + principalId)
            .query((rs, row) -> 1)
            .list();
    }

    /** The person's unexpired files, and their bytes. */
    Totals totals(UUID principalId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) AS n, coalesce(sum(size_bytes), 0) AS bytes
                  FROM staged_file WHERE principal_id = ? AND expires_at > ?""")
            .params(principalId, ts(now))
            .query((rs, row) -> new Totals(rs.getLong("n"), rs.getLong("bytes")))
            .single();
    }

    /** Deletes the person's expired files. */
    void deleteExpired(UUID principalId, Instant now) {
        jdbc.sql("DELETE FROM staged_file WHERE principal_id = ? AND expires_at <= ?")
            .params(principalId, ts(now))
            .update();
    }

    /** The person's unexpired files among {@code ids}, locked, in the order they were held. */
    List<StagedRow> ofPerson(UUID principalId, List<UUID> ids, Instant now) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + """
                 FROM staged_file WHERE principal_id = :person AND id IN (:ids) AND expires_at > :now
                ORDER BY created_at, id FOR UPDATE""")
            .param("person", principalId)
            .param("ids", ids)
            .param("now", ts(now))
            .query(StagedFileStore::row)
            .list();
    }

    /**
     * Deletes the person's files among {@code ids}, theirs only (a start moved its own already, and another's are not
     * touched); answers how many went.
     */
    int delete(UUID principalId, List<UUID> ids) {
        if (ids.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM staged_file WHERE principal_id = :person AND id IN (:ids)")
            .param("person", principalId)
            .param("ids", ids)
            .update();
    }

    /** Moves a held file into the conversation as a file its person added, and deletes it. */
    void moveInto(StagedRow file, AttachmentStore.AttachmentRow attachment) {
        jdbc.sql("""
                INSERT INTO attachment (id, company_id, conversation_id, file_name, content_type, size_bytes, sha256,
                                        content, uploaded_by_user_id, created_at)
                SELECT ?, ?, ?, file_name, content_type, size_bytes, sha256, content, ?, ?
                  FROM staged_file WHERE id = ?""")
            .params(attachment.id(), attachment.companyId(), attachment.conversationId(),
                    attachment.uploadedByUserId(), ts(attachment.createdAt()), file.id())
            .update();
        jdbc.sql("DELETE FROM staged_file WHERE id = ?").param(file.id()).update();
        signals.changed(attachment.conversationId(), ConversationSignals.Part.ATTACHMENTS);
    }

    @Scheduled(fixedDelayString = "${tinfirm.conversation.staged-file-sweep:PT1M}",
            initialDelayString = "${tinfirm.conversation.staged-file-sweep:PT1M}")
    void scheduledSweep() {
        if (worker) {
            try {
                sweep(time.now());
            }
            catch (RuntimeException ex) {
                log.warn("The pass over the held files failed", ex);
            }
        }
    }

    /** Deletes the files that expired before {@code now}, in every company; answers how many. */
    int sweep(Instant now) {
        List<UUID> companies = transactions.unscoped(() -> jdbc.sql(
                "SELECT company_id FROM staged_file_expired(?, ?)")
            .params(ts(now), BATCH)
            .query(UUID.class)
            .list());
        int deleted = 0;
        for (UUID companyId : companies) {
            try {
                deleted += SystemContext.callInCompany(companyId, () -> transactions.inCompany(companyId,
                        () -> jdbc.sql("DELETE FROM staged_file WHERE expires_at < ?").param(ts(now)).update()));
            }
            catch (RuntimeException ex) {
                log.warn("The held files of company {} could not be swept", companyId, ex);
            }
        }
        return deleted;
    }

    private static StagedRow row(ResultSet rs, int row) throws SQLException {
        return new StagedRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("principal_id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getString("file_name"), rs.getString("content_type"), rs.getLong("size_bytes"),
                rs.getString("sha256"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
