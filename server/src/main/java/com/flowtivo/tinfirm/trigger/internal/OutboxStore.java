package com.flowtivo.tinfirm.trigger.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.trigger.OutboxCause;
import com.flowtivo.tinfirm.trigger.OutboxKind;
import com.flowtivo.tinfirm.trigger.PlaceWriter;

import tools.jackson.databind.json.JsonMapper;

/**
 * SQL access to the write-back's tables (V013, V027; the package 4 contract, §3.12, §3.13, §5.9) under row-level
 * security: {@code outbox} (queued, claimed, delivered, tried again, waiting, held, dropped), {@code outbox_review}
 * (the writes of a result under review) and what a delivered row records: its {@code system_write} row and the
 * status, labels and close it wrote in every poll's snapshot of its subject ({@code trigger_subject}). Its lock order
 * is the caller's ({@link TriggerLocks}): the snapshots, then the conversation, then the outbox row.
 */
@Repository
class OutboxStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** A row's columns, of the table as {@code o}, and what its update's target was answered (package 5). */
    private static final String COLUMNS = "o.id, o.company_id, o.conversation_id, o.system_id, o.binding_id,"
            + " o.subject_key, o.seq, o.kind, o.cause, o.cause_key, o.run_id, o.decision_id, o.output_id,"
            + " o.attachment_id, o.message_id, o.body, o.value, o.url, o.status, o.attempts, o.next_attempt_at,"
            + " o.ambiguous, o.last_error, o.external_id, o.created_at,"
            + " (SELECT t.external_id FROM outbox t WHERE t.id = o.target_outbox_id) AS target_external_id";

    private final JdbcClient jdbc;

    OutboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A row to queue.
     *
     * @param bindingId      the binding that writes it; null for a row its place's bot writes (package 5)
     * @param causeKey       a cause queued twice queues once (T13): {@code RESULT:{runId}}, {@code ANSWER:{messageId}}, …
     * @param writer         {@code BINDING} (package 4's) or {@code BOT} (a chat's, package 5)
     * @param targetCauseKey a {@code CHAT_UPDATE}'s: the cause key of the conversation's post it changes
     */
    record NewRow(UUID systemId, @Nullable UUID bindingId, String subjectKey, OutboxKind kind, OutboxCause cause,
            String causeKey, @Nullable UUID runId, @Nullable UUID decisionId, @Nullable UUID outputId,
            @Nullable UUID attachmentId, @Nullable UUID messageId, @Nullable String body, @Nullable String value,
            @Nullable String url, PlaceWriter.@Nullable Writer writer, @Nullable String targetCauseKey) {

        /** A row its binding writes (package 4's). */
        NewRow(UUID systemId, UUID bindingId, String subjectKey, OutboxKind kind, OutboxCause cause, String causeKey,
                @Nullable UUID runId, @Nullable UUID decisionId, @Nullable UUID outputId, @Nullable UUID attachmentId,
                @Nullable UUID messageId, @Nullable String body, @Nullable String value, @Nullable String url) {
            this(systemId, bindingId, subjectKey, kind, cause, causeKey, runId, decisionId, outputId, attachmentId,
                    messageId, body, value, url, PlaceWriter.Writer.BINDING, null);
        }

        /** Who writes it; a row stored before package 5 named none, and its binding wrote it. */
        PlaceWriter.Writer writtenBy() {
            return writer != null ? writer : PlaceWriter.Writer.BINDING;
        }
    }

    /** A row as stored. */
    record Stored(PlaceWriter.Row row, String causeKey, String status, @Nullable Instant nextAttemptAt,
            @Nullable String lastError, @Nullable String externalId) {
    }

    /** A due row of any company, from {@code outbox_due}. */
    record Due(UUID id, UUID companyId, UUID conversationId) {
    }

    // ---- queueing --------------------------------------------------------------------------------------------------

    /**
     * Queues the row after the conversation's last ({@code seq}), {@code PENDING} or {@code HELD} (its trigger is
     * paused); nothing when its cause was queued before (T13). The caller holds the conversation's lock, so that the
     * order is one. Answers the row, if queued.
     */
    Optional<UUID> insert(UUID companyId, UUID conversationId, NewRow row, boolean held, Instant now) {
        UUID id = Ids.newId();
        int inserted = jdbc.sql("""
                INSERT INTO outbox (id, company_id, conversation_id, system_id, binding_id, subject_key, seq, kind,
                                    cause, cause_key, run_id, decision_id, output_id, attachment_id, message_id, body,
                                    value, url, status, writer, target_outbox_id, created_at, updated_at)
                SELECT :id, :companyId, :conversationId, :systemId, :bindingId, :subjectKey,
                       coalesce((SELECT max(seq) FROM outbox WHERE conversation_id = :conversationId), 0) + 1,
                       :kind, :cause, :causeKey, :runId, :decisionId, :outputId, :attachmentId, :messageId, :body,
                       :value, :url, :status, :writer,
                       (SELECT t.id FROM outbox t WHERE t.conversation_id = :conversationId
                                                  AND t.cause_key = :targetCauseKey),
                       :now, :now
                ON CONFLICT (conversation_id, cause_key) DO NOTHING""")
            .param("id", id)
            .param("companyId", companyId)
            .param("conversationId", conversationId)
            .param("systemId", row.systemId())
            .param("bindingId", row.bindingId())
            .param("subjectKey", row.subjectKey())
            .param("kind", row.kind().name())
            .param("cause", row.cause().name())
            .param("causeKey", row.causeKey())
            .param("runId", row.runId())
            .param("decisionId", row.decisionId())
            .param("outputId", row.outputId())
            .param("attachmentId", row.attachmentId())
            .param("messageId", row.messageId())
            .param("body", row.body())
            .param("value", row.value())
            .param("url", row.url())
            .param("writer", row.writtenBy().name())
            .param("targetCauseKey", row.targetCauseKey())
            .param("status", held ? "HELD" : "PENDING")
            .param("now", TriggerStore.ts(now))
            .update();
        return inserted == 1 ? Optional.of(id) : Optional.empty();
    }

    /** Whether the conversation queued this cause before. */
    boolean queued(UUID companyId, UUID conversationId, String causeKey) {
        return jdbc.sql("SELECT count(*) FROM outbox WHERE company_id = ? AND conversation_id = ? AND cause_key = ?")
            .params(companyId, conversationId, causeKey)
            .query(Integer.class)
            .single() > 0;
    }

    /** How many of the conversation's rows have a cause key that starts with the prefix (package 5's updates). */
    int queuedWithPrefix(UUID companyId, UUID conversationId, String prefix) {
        String pattern = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return jdbc.sql("SELECT count(*) FROM outbox WHERE company_id = ? AND conversation_id = ? AND cause_key LIKE ?")
            .params(companyId, conversationId, pattern)
            .query(Integer.class)
            .single();
    }

    // ---- delivery --------------------------------------------------------------------------------------------------

    /** The due rows of every company, each the oldest undelivered row of its conversation (V013's lookup). */
    List<Due> due(Instant now, int limit) {
        return jdbc.sql("SELECT outbox_id, company_id, conversation_id FROM outbox_due(?, ?)")
            .params(TriggerStore.ts(now), limit)
            .query((rs, n) -> new Due(rs.getObject("outbox_id", UUID.class), rs.getObject("company_id", UUID.class),
                    rs.getObject("conversation_id", UUID.class)))
            .list();
    }

    /**
     * Claims the row until {@code until} when it is still pending, due, unclaimed and the oldest undelivered row of its
     * conversation; another replica's claim, or its lock, makes it skip the row.
     */
    Optional<Stored> claim(UUID companyId, UUID id, Instant now, Instant until) {
        return jdbc.sql("""
                UPDATE outbox o SET claimed_until = :until, updated_at = :now
                 WHERE o.id = (SELECT c.id FROM outbox c
                                WHERE c.id = :id AND c.company_id = :companyId AND c.status = 'PENDING'
                                  AND (c.next_attempt_at IS NULL OR c.next_attempt_at <= :now)
                                  AND (c.claimed_until IS NULL OR c.claimed_until < :now)
                                  FOR UPDATE SKIP LOCKED)
                   AND NOT EXISTS (SELECT 1 FROM outbox e
                                    WHERE e.conversation_id = o.conversation_id AND e.seq < o.seq
                                      AND e.status IN ('PENDING', 'HELD'))
                RETURNING""" + " " + COLUMNS)
            .param("id", id)
            .param("companyId", companyId)
            .param("now", TriggerStore.ts(now))
            .param("until", TriggerStore.ts(until))
            .query(OutboxStore::stored)
            .optional();
    }

    Optional<Stored> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outbox o WHERE o.company_id = ? AND o.id = ?")
            .params(companyId, id)
            .query(OutboxStore::stored)
            .optional();
    }

    /** The row, locked: after its conversation in the lock order. */
    Optional<Stored> lock(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outbox o WHERE o.company_id = ? AND o.id = ? FOR UPDATE OF o")
            .params(companyId, id)
            .query(OutboxStore::stored)
            .optional();
    }

    /** The conversation's rows, in their order. */
    List<Stored> ofConversation(UUID companyId, UUID conversationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM outbox o WHERE o.company_id = ? AND o.conversation_id = ?"
                + " ORDER BY o.seq")
            .params(companyId, conversationId)
            .query(OutboxStore::stored)
            .list();
    }

    /** Written: its external id; a row held meanwhile was written all the same. */
    boolean delivered(UUID companyId, UUID id, @Nullable String externalId, int attempts, Instant now) {
        return jdbc.sql("""
                UPDATE outbox SET status = 'DELIVERED', external_id = ?, attempts = ?, delivered_at = ?,
                                  claimed_until = NULL, ambiguous = false, last_error = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ? AND status IN ('PENDING', 'HELD')""")
            .params(externalId != null ? cut(externalId, 255) : null, Math.min(attempts, 10), TriggerStore.ts(now),
                    TriggerStore.ts(now), companyId, id)
            .update() == 1;
    }

    /** Not written now: tried again at {@code next}, its claim released. */
    void retry(UUID companyId, UUID id, int attempts, Instant next, String error, boolean ambiguous, Instant now) {
        jdbc.sql("""
                UPDATE outbox SET attempts = ?, next_attempt_at = ?, last_error = ?, ambiguous = ambiguous OR ?,
                                  claimed_until = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ? AND status IN ('PENDING', 'HELD')""")
            .params(Math.min(attempts, 10), TriggerStore.ts(next), cut(error, 2000), ambiguous, TriggerStore.ts(now),
                    companyId, id)
            .update();
    }

    /** Waits, without an attempt, until {@code next} (its binding's wait at most) or an event that makes it due. */
    void waiting(UUID companyId, UUID id, Instant next, String why, Instant now) {
        jdbc.sql("""
                UPDATE outbox SET next_attempt_at = ?, last_error = ?, claimed_until = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ? AND status IN ('PENDING', 'HELD')""")
            .params(TriggerStore.ts(next), cut(why, 2000), TriggerStore.ts(now), companyId, id)
            .update();
    }

    /** Its last attempt may have written: the next one looks first ({@code PlaceWriter.written}). */
    void markAmbiguous(UUID companyId, UUID id, Instant now) {
        jdbc.sql("UPDATE outbox SET ambiguous = true, updated_at = ? WHERE company_id = ? AND id = ?"
                + " AND status IN ('PENDING', 'HELD')")
            .params(TriggerStore.ts(now), companyId, id)
            .update();
    }

    /** Held: its trigger is paused (§5.11); released by the resume. */
    void hold(UUID companyId, UUID id, Instant now) {
        jdbc.sql("""
                UPDATE outbox SET status = 'HELD', claimed_until = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ? AND status = 'PENDING'""")
            .params(TriggerStore.ts(now), companyId, id)
            .update();
    }

    /** Dropped for good, with the reason; false when it was delivered or dropped already. */
    boolean drop(UUID companyId, UUID id, String reason, int attempts, Instant now) {
        return jdbc.sql("""
                UPDATE outbox SET status = 'DROPPED', dropped_reason = ?, attempts = ?, claimed_until = NULL,
                                  updated_at = ?
                 WHERE company_id = ? AND id = ? AND status IN ('PENDING', 'HELD')""")
            .params(cut(reason, 2000), Math.min(attempts, 10), TriggerStore.ts(now), companyId, id)
            .update() == 1;
    }

    /** The binding's pending rows are due now: it is bound again. */
    int dueNowOfBinding(UUID companyId, UUID bindingId, Instant now) {
        return jdbc.sql("""
                UPDATE outbox SET next_attempt_at = NULL, updated_at = ?
                 WHERE company_id = ? AND binding_id = ? AND status = 'PENDING' AND next_attempt_at IS NOT NULL""")
            .params(TriggerStore.ts(now), companyId, bindingId)
            .update();
    }

    /** Those of the conversations that have a row to deliver ({@code PENDING}), in the order of their ids. */
    List<UUID> conversationsWithPending(UUID companyId, Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT conversation_id FROM outbox
                 WHERE company_id = :companyId AND conversation_id IN (:ids) AND status = 'PENDING'
                 ORDER BY conversation_id""")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(conversationIds))
            .query(UUID.class)
            .list();
    }

    /**
     * The system's pending rows are due now (package 6): it was resumed, and the rows its pause put off go on.
     */
    int dueNowOfSystem(UUID companyId, UUID systemId, Instant now) {
        return jdbc.sql("""
                UPDATE outbox SET next_attempt_at = NULL, updated_at = ?
                 WHERE company_id = ? AND system_id = ? AND status = 'PENDING' AND next_attempt_at > ?""")
            .params(TriggerStore.ts(now), companyId, systemId, TriggerStore.ts(now))
            .update();
    }

    /** The company's pending rows are due now (package 6): its stop was lifted, and the rows it put off go on. */
    int dueNowOfCompany(UUID companyId, Instant now) {
        return jdbc.sql("""
                UPDATE outbox SET next_attempt_at = NULL, updated_at = ?
                 WHERE company_id = ? AND status = 'PENDING' AND next_attempt_at > ?""")
            .params(TriggerStore.ts(now), companyId, TriggerStore.ts(now))
            .update();
    }

    /** The conversations' pending rows are due now: their trigger was resumed, or they were rebound. */
    int dueNow(UUID companyId, Collection<UUID> conversationIds, Instant now) {
        if (conversationIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("""
                UPDATE outbox SET next_attempt_at = NULL, updated_at = :now
                 WHERE company_id = :companyId AND conversation_id IN (:ids) AND status = 'PENDING'
                   AND next_attempt_at IS NOT NULL""")
            .param("now", TriggerStore.ts(now))
            .param("companyId", companyId)
            .param("ids", Set.copyOf(conversationIds))
            .update();
    }

    // ---- the review of a result (T14) -------------------------------------------------------------------------------

    void insertReview(UUID companyId, UUID decisionId, UUID conversationId, UUID runId, List<NewRow> rows,
            Instant now) {
        jdbc.sql("""
                INSERT INTO outbox_review (decision_id, company_id, conversation_id, run_id, writes, created_at)
                VALUES (?, ?, ?, ?, CAST(? AS jsonb), ?)""")
            .params(decisionId, companyId, conversationId, runId, JSON.writeValueAsString(rows),
                    TriggerStore.ts(now))
            .update();
    }

    /** The review's rows, taken: its row goes. */
    Optional<List<NewRow>> takeReview(UUID companyId, UUID decisionId) {
        return jdbc.sql("DELETE FROM outbox_review WHERE company_id = ? AND decision_id = ? RETURNING writes::text")
            .params(companyId, decisionId)
            .query(String.class)
            .optional()
            .map(json -> List.of(JSON.readValue(json, NewRow[].class)));
    }

    /** The conversation's reviews go with it: their decisions were cancelled. */
    int dropReviews(UUID companyId, UUID conversationId) {
        return jdbc.sql("DELETE FROM outbox_review WHERE company_id = ? AND conversation_id = ?")
            .params(companyId, conversationId)
            .update();
    }

    // ---- what a write changes in the polls' snapshots (§5.9) -----------------------------------------------------

    /**
     * What the write changed in every poll's snapshot of the subject on the system (§5.9): the status entered, a label
     * added or removed (ignoring case), the close; so that the next poll finds no edge in it.
     */
    int updateSnapshots(UUID companyId, UUID systemId, String subjectKey, @Nullable String status,
            List<String> added, List<String> removed, boolean closed) {
        int updated = 0;
        if (status != null) {
            updated += jdbc.sql("""
                    UPDATE trigger_subject s SET status = ?
                      FROM trigger_poll p
                     WHERE s.company_id = ? AND s.poll_id = p.id AND p.system_id = ? AND s.key = ?""")
                .params(cut(status, 255), companyId, systemId, subjectKey)
                .update();
        }
        for (String label : removed) {
            updated += jdbc.sql("""
                    UPDATE trigger_subject s
                       SET labels = coalesce((SELECT jsonb_agg(l.value ORDER BY l.ordinality)
                                                FROM jsonb_array_elements_text(s.labels) WITH ORDINALITY AS l(value, ordinality)
                                               WHERE lower(l.value) <> lower(?)), '[]'::jsonb)
                      FROM trigger_poll p
                     WHERE s.company_id = ? AND s.poll_id = p.id AND p.system_id = ? AND s.key = ?""")
                .params(label, companyId, systemId, subjectKey)
                .update();
        }
        for (String label : added) {
            updated += jdbc.sql("""
                    UPDATE trigger_subject s SET labels = s.labels || to_jsonb(CAST(? AS text))
                      FROM trigger_poll p
                     WHERE s.company_id = ? AND s.poll_id = p.id AND p.system_id = ? AND s.key = ?
                       AND NOT EXISTS (SELECT 1 FROM jsonb_array_elements_text(s.labels) l
                                        WHERE lower(l) = lower(?))""")
                .params(label, companyId, systemId, subjectKey, label)
                .update();
        }
        if (closed) {
            updated += jdbc.sql("""
                    UPDATE trigger_subject s SET open = false
                      FROM trigger_poll p
                     WHERE s.company_id = ? AND s.poll_id = p.id AND p.system_id = ? AND s.key = ?""")
                .params(companyId, systemId, subjectKey)
                .update();
        }
        return updated;
    }

    // ---- mapping ---------------------------------------------------------------------------------------------------

    private static Stored stored(ResultSet rs, int rowNum) throws SQLException {
        PlaceWriter.Row row = new PlaceWriter.Row(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("conversation_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getObject("binding_id", UUID.class),
                rs.getString("subject_key"), rs.getInt("seq"), OutboxKind.valueOf(rs.getString("kind")),
                OutboxCause.valueOf(rs.getString("cause")), rs.getObject("run_id", UUID.class),
                rs.getObject("decision_id", UUID.class), rs.getObject("output_id", UUID.class),
                rs.getObject("attachment_id", UUID.class), rs.getObject("message_id", UUID.class),
                rs.getString("body"), rs.getString("value"), rs.getString("url"), rs.getInt("attempts"),
                rs.getBoolean("ambiguous"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getString("target_external_id"));
        OffsetDateTime next = rs.getObject("next_attempt_at", OffsetDateTime.class);
        return new Stored(row, rs.getString("cause_key"), rs.getString("status"),
                next != null ? next.toInstant() : null, rs.getString("last_error"), rs.getString("external_id"));
    }

    static String cut(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max - 1) + "…";
    }
}
