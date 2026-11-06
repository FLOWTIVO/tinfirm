package com.flowtivo.tinfirm.trigger.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.trigger.FiringOutcome;
import com.flowtivo.tinfirm.trigger.TriggerEvent;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * SQL access to part b's tables (V012): {@code trigger_poll}, {@code trigger_subject}, {@code system_write}, the
 * firings it writes into {@code trigger_firing}, and a schedule's columns of {@code trigger}, under row-level security
 * in the caller's tenant context. A poll's and a snapshot's rows come first in the lock order ({@link TriggerLocks}).
 */
@Repository
class PollStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String POLL_COLUMNS = "id, company_id, system_id, account_id, holder_principal_id, since,"
            + " offset_keys, cursor, etags::text AS etags, status, next_poll_at, last_polled_at, lease_until, failures,"
            + " backoff_until, failing_since, last_error, created_at";

    private static final String SUBJECT_COLUMNS = "subject_id, key, kind, open, status, type, labels::text AS labels,"
            + " assignees::text AS assignees, text_hash, head_sha, created_by_tinfirm_depth, last_comment_id,"
            + " last_comment_at, source_updated_at, seen_at";

    private final JdbcClient jdbc;

    PollStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- polls -----------------------------------------------------------------------------------------------------

    /** A poll's row. */
    record PollRow(UUID id, UUID companyId, UUID systemId, UUID accountId, @Nullable UUID holderPrincipalId,
            @Nullable Instant since, List<String> offsetKeys, @Nullable String cursor, Map<String, String> etags,
            String status, Instant nextPollAt, @Nullable Instant lastPolledAt, @Nullable Instant leaseUntil,
            int failures, @Nullable Instant backoffUntil, @Nullable Instant failingSince, @Nullable String lastError,
            Instant createdAt) {
    }

    Optional<PollRow> poll(UUID companyId, UUID pollId) {
        return jdbc.sql("SELECT " + POLL_COLUMNS + " FROM trigger_poll WHERE company_id = ? AND id = ?")
            .params(companyId, pollId)
            .query(PollStore::poll)
            .optional();
    }

    Optional<PollRow> pollOf(UUID companyId, UUID systemId, UUID accountId) {
        return jdbc.sql("SELECT " + POLL_COLUMNS + " FROM trigger_poll WHERE company_id = ? AND system_id = ?"
                + " AND account_id = ?")
            .params(companyId, systemId, accountId)
            .query(PollStore::poll)
            .optional();
    }

    /**
     * The poll of the account, locked; one is made first when there is none ({@code ON CONFLICT DO NOTHING}).
     *
     * @param holderPrincipalId the member whose own account it is; null for a system's credential (ADR-0072)
     */
    PollRow ensure(UUID companyId, UUID systemId, UUID accountId, @Nullable UUID holderPrincipalId, Instant at) {
        jdbc.sql("""
                INSERT INTO trigger_poll (id, company_id, system_id, account_id, holder_principal_id, next_poll_at,
                                          created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (system_id, account_id) DO NOTHING""")
            .params(com.flowtivo.tinfirm.shared.id.Ids.newId(), companyId, systemId, accountId, holderPrincipalId,
                    ts(at), ts(at), ts(at))
            .update();
        return jdbc.sql("SELECT " + POLL_COLUMNS + " FROM trigger_poll WHERE company_id = ? AND system_id = ?"
                + " AND account_id = ? FOR UPDATE")
            .params(companyId, systemId, accountId)
            .query(PollStore::poll)
            .single();
    }

    /**
     * Claims a due poll for a pass ({@code FOR UPDATE SKIP LOCKED}, due checked again): its lease until the given
     * time. Empty when another replica holds it or it is not due; with {@code force}, a poll that is not due is claimed
     * too, unless another pass holds its lease.
     */
    Optional<PollRow> claim(UUID companyId, UUID pollId, Instant now, Instant leaseUntil, boolean force) {
        Optional<PollRow> row = jdbc.sql("SELECT " + POLL_COLUMNS + " FROM trigger_poll WHERE company_id = ? AND id = ?"
                + " FOR UPDATE SKIP LOCKED")
            .params(companyId, pollId)
            .query(PollStore::poll)
            .optional()
            .filter(poll -> poll.leaseUntil() == null || poll.leaseUntil().isBefore(now))
            .filter(poll -> force || "ACTIVE".equals(poll.status()) && !poll.nextPollAt().isAfter(now)
                    && (poll.backoffUntil() == null || !poll.backoffUntil().isAfter(now)));
        row.ifPresent(poll -> jdbc.sql("UPDATE trigger_poll SET lease_until = ?, updated_at = ? WHERE id = ?")
            .params(ts(leaseUntil), ts(now), poll.id())
            .update());
        return row;
    }

    /** The window after a pass, its next time, and its health; the lease ends. */
    void finish(UUID pollId, PollWindow window, Map<String, String> etags, Instant lastPolledAt, Instant nextPollAt,
            int failures, @Nullable Instant backoffUntil, @Nullable Instant failingSince, @Nullable String lastError,
            SystemPolls.@Nullable RateLimit rate, Instant at) {
        jdbc.sql("""
                UPDATE trigger_poll
                   SET since = ?, offset_keys = ?, cursor = ?, etags = CAST(? AS jsonb), last_polled_at = ?,
                       next_poll_at = ?, lease_until = NULL, failures = ?, backoff_until = ?, failing_since = ?,
                       last_error = ?, rate_limit = coalesce(?, rate_limit), rate_remaining = coalesce(?, rate_remaining),
                       rate_resets_at = coalesce(?, rate_resets_at), updated_at = ?
                 WHERE id = ?""")
            .params(ts(window.since()), window.offsetKeys().toArray(String[]::new), cut(window.cursor(), 2000),
                    JSON.writeValueAsString(etags), ts(lastPolledAt), ts(nextPollAt), failures, ts(backoffUntil),
                    ts(failingSince), cut(lastError, 2000), rate != null ? rate.limit() : null,
                    rate != null ? rate.remaining() : null, rate != null ? ts(rate.resetsAt()) : null, ts(at), pollId)
            .update();
    }

    /** The poll's status: {@code ACTIVE}, {@code IDLE} or {@code WAITING}; the lease ends. */
    void status(UUID pollId, String status, @Nullable String lastError, Instant nextPollAt, Instant at) {
        jdbc.sql("""
                UPDATE trigger_poll SET status = ?, last_error = coalesce(?, last_error), next_poll_at = ?,
                                        lease_until = NULL, updated_at = ?
                 WHERE id = ?""")
            .params(status, cut(lastError, 2000), ts(nextPollAt), ts(at), pollId)
            .update();
    }

    /** An idle poll's window moves to now: triggers added later apply to changes from then on. */
    void idle(UUID pollId, Instant since, Instant nextPollAt, Instant at) {
        jdbc.sql("""
                UPDATE trigger_poll SET status = 'IDLE', since = greatest(coalesce(since, ?), ?), offset_keys = '{}',
                                        cursor = NULL, lease_until = NULL, next_poll_at = ?, updated_at = ?
                 WHERE id = ?""")
            .params(ts(since), ts(since), ts(nextPollAt), ts(at), pollId)
            .update();
    }

    /** Active again (a trigger on it armed, its account connected again, its holder resumed), due now. */
    int activate(UUID pollId, Instant at) {
        return jdbc.sql("""
                UPDATE trigger_poll SET status = 'ACTIVE', next_poll_at = least(next_poll_at, ?), updated_at = ?
                 WHERE id = ? AND status <> 'ACTIVE' OR id = ? AND next_poll_at > ?""")
            .params(ts(at), ts(at), pollId, pollId, ts(at))
            .update();
    }

    /** The polls of the account. */
    List<UUID> pollsOfAccount(UUID companyId, UUID accountId) {
        return jdbc.sql("SELECT id FROM trigger_poll WHERE company_id = ? AND account_id = ? ORDER BY id")
            .params(companyId, accountId)
            .query(UUID.class)
            .list();
    }

    /** The polls whose holder is the principal. */
    List<UUID> pollsOfHolder(UUID companyId, UUID principalId) {
        return jdbc.sql("SELECT id FROM trigger_poll WHERE company_id = ? AND holder_principal_id = ? ORDER BY id")
            .params(companyId, principalId)
            .query(UUID.class)
            .list();
    }

    /** The polls of the system. */
    List<UUID> pollsOfSystem(UUID companyId, UUID systemId) {
        return jdbc.sql("SELECT id FROM trigger_poll WHERE company_id = ? AND system_id = ? ORDER BY id")
            .params(companyId, systemId)
            .query(UUID.class)
            .list();
    }

    /**
     * The windows of the system's polls move to {@code since}, never back (a paused system's resume, the package 6
     * contract, §5.4): what changed during the pause is history. Locks the polls' rows, in the order of their ids.
     *
     * @return the polls moved
     */
    int moveWindows(UUID companyId, UUID systemId, Instant since, Instant at) {
        return jdbc.sql("""
                UPDATE trigger_poll SET since = greatest(coalesce(since, ?), ?), offset_keys = '{}', cursor = NULL,
                                        updated_at = ?
                 WHERE id IN (SELECT id FROM trigger_poll WHERE company_id = ? AND system_id = ? ORDER BY id
                                 FOR UPDATE)""")
            .params(ts(since), ts(since), ts(at), companyId, systemId)
            .update();
    }

    // ---- snapshots -------------------------------------------------------------------------------------------------

    /**
     * The snapshot of the subject, locked for the subject's transaction: the read's snapshot is inserted when the poll
     * has none (then the answer is empty: no previous snapshot), else the existing one is locked and answered. Either
     * way the row is held until the transaction ends, so that a second pass waits and then finds no edge (T10).
     */
    Optional<SubjectSnapshot> lockOrInsert(UUID companyId, UUID pollId, SubjectSnapshot read) {
        int inserted = jdbc.sql("""
                INSERT INTO trigger_subject (company_id, poll_id, subject_id, key, kind, open, status, type, labels,
                                             assignees, text_hash, head_sha, created_by_tinfirm_depth, last_comment_id,
                                             last_comment_at, source_updated_at, seen_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (poll_id, subject_id) DO NOTHING""")
            .params(snapshotParams(companyId, pollId, read))
            .update();
        if (inserted == 1) {
            return Optional.empty();
        }
        return jdbc.sql("SELECT " + SUBJECT_COLUMNS + " FROM trigger_subject WHERE poll_id = ? AND subject_id = ?"
                + " FOR UPDATE")
            .params(pollId, read.subjectId())
            .query(PollStore::snapshot)
            .optional();
    }

    /** The snapshots of the subjects, without a lock: which changed since they were read. */
    Map<String, SubjectSnapshot> snapshots(UUID pollId, Collection<String> subjectIds) {
        Map<String, SubjectSnapshot> found = new LinkedHashMap<>();
        if (subjectIds.isEmpty()) {
            return found;
        }
        jdbc.sql("SELECT " + SUBJECT_COLUMNS + " FROM trigger_subject WHERE poll_id = :pollId"
                + " AND subject_id IN (:ids)")
            .param("pollId", pollId)
            .param("ids", Set.copyOf(subjectIds))
            .query(PollStore::snapshot)
            .list()
            .forEach(snapshot -> found.put(snapshot.subjectId(), snapshot));
        return found;
    }

    void updateSnapshot(UUID pollId, SubjectSnapshot snapshot) {
        jdbc.sql("""
                UPDATE trigger_subject
                   SET key = ?, kind = ?, open = ?, status = ?, type = ?, labels = CAST(? AS jsonb),
                       assignees = CAST(? AS jsonb), text_hash = ?, head_sha = ?, created_by_tinfirm_depth = ?,
                       last_comment_id = ?, last_comment_at = ?, source_updated_at = ?, seen_at = ?
                 WHERE poll_id = ? AND subject_id = ?""")
            .params(cut(snapshot.key(), 255), snapshot.kind(), snapshot.open(), cut(snapshot.status(), 255),
                    cut(snapshot.type(), 255), labels(snapshot.labels()), assignees(snapshot.assignees()),
                    snapshot.textHash(), cut(snapshot.headSha(), 64), snapshot.createdByTinfirmDepth(),
                    cut(snapshot.lastCommentId(), 255), ts(snapshot.lastCommentAt()), ts(snapshot.sourceUpdatedAt()),
                    ts(snapshot.seenAt()), pollId, snapshot.subjectId())
            .update();
    }

    /** The subjects read again unchanged: their snapshot is not swept. */
    void touch(UUID pollId, Collection<String> subjectIds, Instant at) {
        if (subjectIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE trigger_subject SET seen_at = greatest(seen_at, :at) WHERE poll_id = :pollId"
                + " AND subject_id IN (:ids)")
            .param("at", ts(at))
            .param("pollId", pollId)
            .param("ids", Set.copyOf(subjectIds))
            .update();
    }

    /** Snapshots unread for 90 days and records of writes older than 90 days go. */
    void sweep(UUID companyId, UUID pollId, Instant before) {
        jdbc.sql("DELETE FROM trigger_subject WHERE poll_id = ? AND seen_at < ?")
            .params(pollId, ts(before))
            .update();
        jdbc.sql("DELETE FROM system_write WHERE company_id = ? AND written_at < ?")
            .params(companyId, ts(before))
            .update();
    }

    private Object[] snapshotParams(UUID companyId, UUID pollId, SubjectSnapshot snapshot) {
        return new Object[] {companyId, pollId, cut(snapshot.subjectId(), 255), cut(snapshot.key(), 255),
            snapshot.kind(), snapshot.open(), cut(snapshot.status(), 255), cut(snapshot.type(), 255),
            labels(snapshot.labels()), assignees(snapshot.assignees()), snapshot.textHash(),
            cut(snapshot.headSha(), 64), snapshot.createdByTinfirmDepth(), cut(snapshot.lastCommentId(), 255),
            ts(snapshot.lastCommentAt()), ts(snapshot.sourceUpdatedAt()), ts(snapshot.seenAt())};
    }

    // ---- Tinfirm's writes ------------------------------------------------------------------------------------------

    /** A write of Tinfirm's to record ({@code system_write}). */
    record Write(UUID companyId, UUID systemId, String subjectKey, String kind, @Nullable String externalId,
            @Nullable String status, List<String> labelsAdded, List<String> labelsRemoved,
            @Nullable UUID conversationId, @Nullable Integer chainDepth, Instant writtenAt) {
    }

    void insertWrite(Write write) {
        ObjectNode values = JSON.createObjectNode();
        if (write.status() != null) {
            values.put("status", write.status());
        }
        if (!write.labelsAdded().isEmpty()) {
            ArrayNode added = values.putArray("labelsAdded");
            write.labelsAdded().forEach(added::add);
        }
        if (!write.labelsRemoved().isEmpty()) {
            ArrayNode removed = values.putArray("labelsRemoved");
            write.labelsRemoved().forEach(removed::add);
        }
        jdbc.sql("""
                INSERT INTO system_write (id, company_id, system_id, subject_key, kind, external_id, written_values,
                                          conversation_id, chain_depth, written_at)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)""")
            .params(com.flowtivo.tinfirm.shared.id.Ids.newId(), write.companyId(), write.systemId(),
                    cut(write.subjectKey(), 255), write.kind(), cut(write.externalId(), 255),
                    JSON.writeValueAsString(values), write.conversationId(),
                    "CHANGE_REQUEST".equals(write.kind()) || "ISSUE".equals(write.kind()) ? write.chainDepth() : null,
                    ts(write.writtenAt()))
            .update();
    }

    /**
     * What Tinfirm wrote to the subject: every comment's id, and the statuses, labels, closes and pushes recorded from
     * {@code since} on (the snapshot's read, minus the overlap).
     */
    EventDetector.OwnWrites ownWrites(UUID systemId, String subjectKey, @Nullable String headSha, Instant since) {
        Set<String> comments = new LinkedHashSet<>();
        Set<String> statuses = new LinkedHashSet<>();
        Set<String> added = new LinkedHashSet<>();
        Set<String> removed = new LinkedHashSet<>();
        Set<String> pushed = new LinkedHashSet<>();
        boolean[] statusUnknown = {false};
        boolean[] closed = {false};
        jdbc.sql("""
                SELECT kind, external_id, written_values::text AS written_values, written_at FROM system_write
                 WHERE system_id = ? AND lower(subject_key) = lower(?)""")
            .params(systemId, subjectKey)
            .query(rs -> {
                String kind = rs.getString("kind");
                String externalId = rs.getString("external_id");
                boolean recent = !rs.getObject("written_at", OffsetDateTime.class).toInstant().isBefore(since);
                JsonNode values = JSON.readTree(rs.getString("written_values"));
                switch (kind) {
                    case "COMMENT" -> {
                        if (externalId != null) {
                            comments.add(externalId);
                        }
                    }
                    case "TRANSITION" -> {
                        if (recent) {
                            String status = values.path("status").isString() ? values.get("status").stringValue()
                                    : null;
                            if (status != null) {
                                statuses.add(status);
                            }
                            else {
                                statusUnknown[0] = true;
                            }
                        }
                    }
                    case "LABEL" -> {
                        if (recent) {
                            values.path("labelsAdded").forEach(label -> added.add(label.asString()));
                            values.path("labelsRemoved").forEach(label -> removed.add(label.asString()));
                        }
                    }
                    case "CLOSE" -> closed[0] |= recent;
                    default -> {
                    }
                }
            });
        if (headSha != null) {
            pushed.addAll(jdbc.sql("SELECT external_id FROM system_write WHERE system_id = ? AND external_id = ?"
                    + " AND kind = 'PUSH'")
                .params(systemId, headSha)
                .query(String.class)
                .list());
        }
        return new EventDetector.OwnWrites(comments, statuses, statusUnknown[0], added, removed, closed[0], pushed);
    }

    /** The conversation that created the subject and its depth, when Tinfirm created it ({@code ISSUE}, a change request). */
    record Creator(@Nullable UUID conversationId, int depth) {
    }

    Optional<Creator> creator(UUID systemId, String subjectKey) {
        return jdbc.sql("""
                SELECT conversation_id, coalesce(chain_depth, 0) AS depth FROM system_write
                 WHERE system_id = ? AND lower(subject_key) = lower(?) AND kind IN ('CHANGE_REQUEST', 'ISSUE')
                 ORDER BY written_at LIMIT 1""")
            .params(systemId, subjectKey)
            .query((rs, n) -> new Creator(rs.getObject("conversation_id", UUID.class), rs.getInt("depth")))
            .optional();
    }

    // ---- firings ---------------------------------------------------------------------------------------------------

    /** A firing to record. */
    record Firing(UUID id, UUID companyId, UUID triggerId, @Nullable String subjectId, @Nullable String subjectKey,
            TriggerEvent event, @Nullable String value, @Nullable Map<String, @Nullable String> actor,
            @Nullable String commentExternalId, @Nullable Instant changedAt, @Nullable Instant dueAt,
            @Nullable UUID manualBy, boolean once, FiringOutcome outcome, @Nullable String reason, int chainDepth,
            @Nullable String request, @Nullable String subjectSummary, @Nullable UUID conversationId, Instant at,
            @Nullable UUID actorPrincipalId) {

        /** A tracker's or a schedule's firing, whose actor is no member (package 4). */
        Firing(UUID id, UUID companyId, UUID triggerId, @Nullable String subjectId, @Nullable String subjectKey,
                TriggerEvent event, @Nullable String value, @Nullable Map<String, @Nullable String> actor,
                @Nullable String commentExternalId, @Nullable Instant changedAt, @Nullable Instant dueAt,
                @Nullable UUID manualBy, boolean once, FiringOutcome outcome, @Nullable String reason, int chainDepth,
                @Nullable String request, @Nullable String subjectSummary, @Nullable UUID conversationId,
                Instant at) {
            this(id, companyId, triggerId, subjectId, subjectKey, event, value, actor, commentExternalId, changedAt,
                    dueAt, manualBy, once, outcome, reason, chainDepth, request, subjectSummary, conversationId, at,
                    null);
        }
    }

    /** Inserts the firing; false when a {@code ONCE} firing of the trigger on the subject was there first (T10). */
    boolean insertFiring(Firing firing) {
        String actor = null;
        if (firing.actor() != null) {
            ObjectNode node = JSON.createObjectNode();
            firing.actor().forEach((key, value) -> {
                if (value != null) {
                    node.put(key, cut(value, 255));
                }
            });
            actor = JSON.writeValueAsString(node);
        }
        return jdbc.sql("""
                INSERT INTO trigger_firing (id, company_id, trigger_id, subject_id, subject_key, event, value, actor,
                                            comment_external_id, changed_at, due_at, manual_by, once, outcome, reason,
                                            chain_depth, request, subject_summary, conversation_id, created_at,
                                            actor_principal_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (trigger_id, subject_id) WHERE once DO NOTHING""")
            .params(firing.id(), firing.companyId(), firing.triggerId(), cut(firing.subjectId(), 255),
                    cut(firing.subjectKey(), 255), firing.event().name(), cut(firing.value(), 255), actor,
                    cut(firing.commentExternalId(), 255), ts(firing.changedAt()), ts(firing.dueAt()),
                    firing.manualBy(), firing.once(), firing.outcome().name(), cut(firing.reason(), 2000),
                    firing.chainDepth(), cut(firing.request(), 20_000), cut(firing.subjectSummary(), 16_384),
                    firing.conversationId(), ts(firing.at()), firing.actorPrincipalId())
            .update() == 1;
    }

    /** The {@code ONCE} triggers among these that fired on the subject. */
    Set<UUID> onceFired(Collection<UUID> triggerIds, String subjectId) {
        if (triggerIds.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("SELECT trigger_id FROM trigger_firing WHERE once AND subject_id = :subjectId"
                + " AND trigger_id IN (:ids)")
            .param("subjectId", subjectId)
            .param("ids", Set.copyOf(triggerIds))
            .query(UUID.class)
            .list());
    }

    /** The trigger's firings that took a conversation ({@code STARTED}, {@code CONTINUED}) since then: its rate. */
    int firedSince(UUID triggerId, Instant since) {
        return jdbc.sql("""
                SELECT count(*) FROM trigger_firing
                 WHERE trigger_id = ? AND outcome IN ('STARTED', 'CONTINUED') AND created_at >= ?""")
            .params(triggerId, ts(since))
            .query(Integer.class)
            .single();
    }

    /** Whether the trigger recorded a firing of that outcome since then. */
    boolean recordedSince(UUID triggerId, FiringOutcome outcome, Instant since) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM trigger_firing WHERE trigger_id = ? AND outcome = ? AND created_at >= ?)""")
            .params(triggerId, outcome.name(), ts(since))
            .query(Boolean.class)
            .single();
    }

    /** The events of the firings a run took. */
    List<TriggerEvent> eventsTaken(UUID runId) {
        return jdbc.sql("SELECT event FROM trigger_firing WHERE run_id = ?")
            .params(runId)
            .query((rs, n) -> TriggerEvent.valueOf(rs.getString("event")))
            .list();
    }

    void countMissed(UUID triggerId, int count) {
        jdbc.sql("UPDATE trigger SET missed_count = missed_count + ? WHERE id = ?")
            .params(count, triggerId)
            .update();
    }

    void countRateSkipped(UUID triggerId) {
        jdbc.sql("UPDATE trigger SET rate_skipped_count = rate_skipped_count + 1 WHERE id = ?")
            .params(triggerId)
            .update();
    }

    // ---- schedules -------------------------------------------------------------------------------------------------

    /** The schedule's row lock, {@code SKIP LOCKED}: empty when another replica holds it. */
    boolean lockSchedule(UUID companyId, UUID triggerId) {
        return jdbc.sql("SELECT id FROM trigger WHERE company_id = ? AND id = ? FOR NO KEY UPDATE SKIP LOCKED")
            .params(companyId, triggerId)
            .query(UUID.class)
            .optional()
            .isPresent();
    }

    /** What a schedule's firing did, and its next due time. */
    void scheduled(UUID triggerId, @Nullable Instant nextRunAt, Instant lastRunAt, FiringOutcome outcome, Instant at) {
        jdbc.sql("""
                UPDATE trigger SET next_run_at = ?, last_run_at = ?, last_outcome = ?, updated_at = ?
                 WHERE id = ? AND deleted_at IS NULL""")
            .params(ts(nextRunAt), ts(lastRunAt), outcome.name(), ts(at), triggerId)
            .update();
    }

    void nextRun(UUID triggerId, @Nullable Instant nextRunAt) {
        jdbc.sql("UPDATE trigger SET next_run_at = ? WHERE id = ? AND deleted_at IS NULL")
            .params(ts(nextRunAt), triggerId)
            .update();
    }

    // ---- mapping ---------------------------------------------------------------------------------------------------

    private static PollRow poll(ResultSet rs, int rowNum) throws SQLException {
        Map<String, String> etags = new LinkedHashMap<>();
        JsonNode node = JSON.readTree(rs.getString("etags"));
        node.properties().forEach(entry -> {
            if (entry.getValue().isString()) {
                etags.put(entry.getKey(), entry.getValue().stringValue());
            }
        });
        return new PollRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getObject("account_id", UUID.class),
                rs.getObject("holder_principal_id", UUID.class), nullableInstant(rs, "since"),
                strings(rs.getArray("offset_keys")), rs.getString("cursor"), etags, rs.getString("status"),
                instant(rs, "next_poll_at"), nullableInstant(rs, "last_polled_at"), nullableInstant(rs, "lease_until"),
                rs.getInt("failures"), nullableInstant(rs, "backoff_until"), nullableInstant(rs, "failing_since"),
                rs.getString("last_error"), instant(rs, "created_at"));
    }

    private static SubjectSnapshot snapshot(ResultSet rs, int rowNum) throws SQLException {
        List<String> labels = new ArrayList<>();
        JSON.readTree(rs.getString("labels")).forEach(label -> labels.add(label.asString()));
        List<SystemPolls.User> assignees = new ArrayList<>();
        JSON.readTree(rs.getString("assignees")).forEach(user -> assignees.add(new SystemPolls.User(
                user.path("id").isString() ? user.get("id").stringValue() : null,
                user.path("login").isString() ? user.get("login").stringValue() : null,
                user.path("name").isString() ? user.get("name").stringValue() : null)));
        Object depth = rs.getObject("created_by_tinfirm_depth");
        return new SubjectSnapshot(rs.getString("subject_id"), rs.getString("key"), rs.getString("kind"),
                rs.getBoolean("open"), rs.getString("status"), rs.getString("type"), labels, assignees,
                rs.getString("text_hash"), rs.getString("head_sha"),
                depth != null ? ((Number) depth).intValue() : null, rs.getString("last_comment_id"),
                nullableInstant(rs, "last_comment_at"), nullableInstant(rs, "source_updated_at"),
                instant(rs, "seen_at"));
    }

    private static String labels(List<String> labels) {
        ArrayNode array = JSON.createArrayNode();
        labels.forEach(label -> array.add(cut(label, 255)));
        return JSON.writeValueAsString(array);
    }

    private static String assignees(List<SystemPolls.User> users) {
        ArrayNode array = JSON.createArrayNode();
        for (SystemPolls.User user : users) {
            ObjectNode node = array.addObject();
            if (user.id() != null) {
                node.put("id", cut(user.id(), 255));
            }
            if (user.login() != null) {
                node.put("login", cut(user.login(), 255));
            }
            if (user.name() != null) {
                node.put("name", cut(user.name(), 255));
            }
        }
        return JSON.writeValueAsString(array);
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        List<String> values = new ArrayList<>();
        if (array != null) {
            for (Object value : (Object[]) array.getArray()) {
                values.add(String.valueOf(value));
            }
        }
        return values;
    }

    static @Nullable String cut(@Nullable String text, int max) {
        if (text == null || text.codePointCount(0, text.length()) <= max) {
            return text;
        }
        return text.substring(0, text.offsetByCodePoints(0, max));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return TriggerStore.ts(instant);
    }
}
