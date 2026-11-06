package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.run.RunPurge;

/**
 * {@link RunPurge} on {@code run} and {@code run_event} (V023's markers), under row-level security. Only an ended run
 * ({@code finished_at} set) is purged: a queued or running one is its conversation's live turn. Each purge and its
 * count share the predicate of the rows they take ({@link #EVENTS_KEPT}, {@link #TEXTS_KEPT}). A purge of a whole
 * conversation's texts or events also deletes its session bundles (ADR-0097), which hold the same words.
 */
@Component
class RunPurgeImpl implements RunPurge {

    /** An ended run whose events are kept: what a purge of events takes. */
    private static final String EVENTS_KEPT = "finished_at IS NOT NULL AND events_purged_at IS NULL";

    /** An ended run whose texts are kept: what a purge of texts takes. */
    private static final String TEXTS_KEPT = "finished_at IS NOT NULL AND texts_purged_at IS NULL";

    private static final String OF_RUNS = "id = ANY (CAST(? AS uuid[]))";

    private static final String OF_CONVERSATIONS = "conversation_id = ANY (CAST(? AS uuid[]))";

    private final JdbcClient jdbc;

    private final SessionBundleCleanup bundles;

    RunPurgeImpl(JdbcClient jdbc, SessionBundleCleanup bundles) {
        this.jdbc = jdbc;
        this.bundles = bundles;
    }

    @Override
    public List<EndedRun> eventsKept(Instant endedBefore, @Nullable EndedRun after, int limit) {
        List<Object> params = new ArrayList<>();
        params.add(ts(endedBefore));
        String keyset = "";
        if (after != null) {
            keyset = " AND (finished_at, id) > (?, ?)";
            params.add(ts(after.finishedAt()));
            params.add(after.runId());
        }
        params.add(limit);
        return jdbc.sql("SELECT id, conversation_id, finished_at FROM run WHERE " + EVENTS_KEPT + " AND finished_at < ?"
                + keyset + " ORDER BY finished_at, id LIMIT ?")
            .params(params)
            .query((rs, row) -> new EndedRun(rs.getObject("id", UUID.class),
                    rs.getObject("conversation_id", UUID.class),
                    rs.getObject("finished_at", OffsetDateTime.class).toInstant()))
            .list();
    }

    @Override
    public int purgeEvents(Collection<UUID> runIds, Instant at) {
        if (runIds.isEmpty()) {
            return 0;
        }
        List<UUID> purged = jdbc.sql("UPDATE run SET events_purged_at = ? WHERE " + OF_RUNS + " AND " + EVENTS_KEPT
                + " RETURNING id")
            .params(ts(at), ids(runIds))
            .query(UUID.class)
            .list();
        if (!purged.isEmpty()) {
            jdbc.sql("DELETE FROM run_event WHERE run_id = ANY (CAST(? AS uuid[]))").params((Object) ids(purged))
                .update();
        }
        return purged.size();
    }

    @Override
    public int purgeEventsOf(UUID conversationId, Instant at) {
        List<UUID> runs = jdbc.sql("SELECT id FROM run WHERE conversation_id = ? AND " + EVENTS_KEPT)
            .params(conversationId)
            .query(UUID.class)
            .list();
        bundles.purged(conversationId);
        return purgeEvents(runs, at);
    }

    @Override
    public int purgeTextsOf(UUID conversationId, Instant at) {
        bundles.purged(conversationId);
        return jdbc.sql("""
                UPDATE run SET summary = NULL, failure_message = NULL,
                       result_next_steps = CASE WHEN result_next_steps IS NULL THEN NULL ELSE '[]'::jsonb END,
                       result_open_questions = CASE WHEN result_open_questions IS NULL THEN NULL ELSE '[]'::jsonb END,
                       texts_purged_at = ?
                 WHERE conversation_id = ? AND\s""" + TEXTS_KEPT)
            .params(ts(at), conversationId)
            .update();
    }

    @Override
    public long countEvents(Collection<UUID> runIds) {
        if (runIds.isEmpty()) {
            return 0;
        }
        return count("SELECT count(*) FROM run WHERE " + OF_RUNS + " AND " + EVENTS_KEPT, (Object) ids(runIds));
    }

    @Override
    public long countEventsOf(Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return 0;
        }
        return count("SELECT count(*) FROM run WHERE " + OF_CONVERSATIONS + " AND " + EVENTS_KEPT,
                (Object) ids(conversationIds));
    }

    @Override
    public long countTextsOf(Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return 0;
        }
        return count("SELECT count(*) FROM run WHERE " + OF_CONVERSATIONS + " AND " + TEXTS_KEPT,
                (Object) ids(conversationIds));
    }

    @Override
    public long countTextsIn(ConversationSelection conversations) {
        return count("SELECT count(*) FROM run WHERE conversation_id IN (" + conversations.sql() + ") AND "
                + TEXTS_KEPT, conversations.params().toArray());
    }

    @Override
    public long countEventsKeptIn(Instant endedBefore, ConversationSelection conversations) {
        List<Object> params = new ArrayList<>();
        params.add(ts(endedBefore));
        params.addAll(conversations.params());
        return count("SELECT count(*) FROM run WHERE " + EVENTS_KEPT + " AND finished_at < ? AND conversation_id IN ("
                + conversations.sql() + ")", params.toArray());
    }

    private long count(String sql, Object... params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static String[] ids(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toArray(String[]::new);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
