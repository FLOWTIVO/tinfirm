package com.flowtivo.tinfirm.conversation.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.system.ToolEffect;
import com.flowtivo.tinfirm.system.ToolReplay;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The call journal ({@code tool_call}, V026; package R, §3.2): one row per {@code TOOL} request of a run that reached
 * its call, in the caller's transaction and tenant context. Its row is the last of a conversation's rows a transaction
 * locks, after the conversation, its runs, bindings and decisions, before the audit chain head (CONVENTIONS §10); the
 * claim's own transaction locks nothing but the row. A memory tool's write ({@link MemoryTools}, V044) has a row too,
 * without a system, claimed and answered in the transaction that writes the memory.
 */
@Component
class ToolCallStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String COLUMNS = """
            company_id, run_id, request_id, conversation_id, system_id, tool, name, effect, replay, arguments_sha256,
            state, attempt, started_at, finished_at, result::text AS result, run_ended_at""";

    private final JdbcClient jdbc;

    ToolCallStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** {@code RUNNING} (claimed, under way), {@code DONE} (answered), {@code INTERRUPTED} (lost track of). */
    enum State {
        RUNNING, DONE, INTERRUPTED
    }

    /**
     * A row of the journal.
     *
     * @param systemId the system that serves the tool; null for a memory tool's ({@link MemoryTools})
     * @param tool     the tool as its system names it
     * @param name     the tool as the run lists it
     * @param result   the answer as it went out ({@link ToolJournal#stored}); null while running, for a {@code SAFE}
     *                 call the sweep set {@code INTERRUPTED}, and once the run ended
     */
    record Row(UUID companyId, UUID runId, UUID requestId, UUID conversationId, @Nullable UUID systemId, String tool,
            String name, ToolEffect effect, ToolReplay replay, String argumentsSha256, State state, int attempt,
            Instant startedAt, @Nullable Instant finishedAt, @Nullable JsonNode result, @Nullable Instant runEndedAt) {
    }

    Optional<Row> find(UUID runId, UUID requestId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tool_call WHERE run_id = ? AND request_id = ?")
            .params(runId, requestId)
            .query(ToolCallStore::row)
            .optional();
    }

    /** The row, locked for its change ({@code FOR NO KEY UPDATE}: no key of it changes). */
    Optional<Row> lock(UUID runId, UUID requestId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM tool_call WHERE run_id = ? AND request_id = ? FOR NO KEY UPDATE")
            .params(runId, requestId)
            .query(ToolCallStore::row)
            .optional();
    }

    /** Inserts the claim of a new call, {@code RUNNING} at attempt 1; false when the request has a row already. */
    boolean insert(Row row) {
        return jdbc.sql("""
                INSERT INTO tool_call (company_id, run_id, request_id, conversation_id, system_id, tool, name, effect,
                                       replay, arguments_sha256, state, attempt, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'RUNNING', 1, ?)
                ON CONFLICT (run_id, request_id) DO NOTHING""")
            .params(row.companyId(), row.runId(), row.requestId(), row.conversationId(), row.systemId(), row.tool(),
                    row.name(), row.effect().name(), row.replay().name(), row.argumentsSha256(),
                    DecisionStore.ts(row.startedAt()))
            .update() == 1;
    }

    /** Claims the call again, one attempt more, when it is still at {@code attempt} and not running anew. */
    boolean reclaim(UUID runId, UUID requestId, int attempt, Instant at) {
        return jdbc.sql("""
                UPDATE tool_call SET state = 'RUNNING', attempt = attempt + 1, started_at = ?, finished_at = NULL,
                                     result = NULL
                 WHERE run_id = ? AND request_id = ? AND attempt = ?""")
            .params(DecisionStore.ts(at), runId, requestId, attempt)
            .update() == 1;
    }

    /**
     * Ends the attempt with its answer, when the row is still that attempt running: false when another settled it
     * (a timeout, the sweep, another replica's claim again). A row whose run ended keeps no answer: the run's end
     * cleared it, and a call that ends later stores none.
     */
    boolean finish(UUID runId, UUID requestId, int attempt, State state, @Nullable JsonNode result, Instant at) {
        return jdbc.sql("""
                UPDATE tool_call SET state = ?, finished_at = ?,
                                     result = CASE WHEN run_ended_at IS NULL THEN CAST(? AS jsonb) END
                 WHERE run_id = ? AND request_id = ? AND state = 'RUNNING' AND attempt = ?""")
            .params(state.name(), DecisionStore.ts(at), result != null ? result.toString() : null, runId, requestId,
                    attempt)
            .update() == 1;
    }

    /** The run ended: its rows' answers are cleared, and their retention counts from now. */
    int runEnded(UUID runId, Instant at) {
        return jdbc.sql("UPDATE tool_call SET result = NULL, run_ended_at = ? WHERE run_id = ?")
            .params(DecisionStore.ts(at), runId)
            .update();
    }

    /** A run lost by lease expiry runs again: its rows are kept as long as it runs. */
    int runResumed(UUID runId) {
        return jdbc.sql("UPDATE tool_call SET run_ended_at = NULL WHERE run_id = ?").param(runId).update();
    }

    /**
     * Deletes at most {@code limit} of the company's rows past their retention: of runs that ended before the cutoff,
     * and rows claimed before it whose run's end never reached them (a claim that raced the end).
     */
    int deleteExpired(Instant cutoff, int limit) {
        return jdbc.sql("""
                DELETE FROM tool_call
                 WHERE (run_id, request_id) IN (
                        SELECT run_id, request_id FROM tool_call
                         WHERE run_ended_at < :cutoff OR (run_ended_at IS NULL AND started_at < :cutoff)
                         LIMIT :limit)""")
            .param("cutoff", DecisionStore.ts(cutoff))
            .param("limit", limit)
            .update();
    }

    /** Deletes a conversation's rows: its purge or erasure. */
    int deleteOfConversation(UUID conversationId) {
        return jdbc.sql("DELETE FROM tool_call WHERE conversation_id = ?").param(conversationId).update();
    }

    /** Deletes the rows of these runs, with their events. */
    int deleteOfRuns(Collection<UUID> runIds) {
        if (runIds.isEmpty()) {
            return 0;
        }
        return jdbc.sql("DELETE FROM tool_call WHERE run_id = ANY (CAST(? AS uuid[]))")
            .param((Object) runIds.stream().map(UUID::toString).toArray(String[]::new))
            .update();
    }

    private static Row row(ResultSet rs, int rowNum) throws SQLException {
        String result = rs.getString("result");
        return new Row(rs.getObject("company_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("request_id", UUID.class), rs.getObject("conversation_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getString("tool"), rs.getString("name"),
                ToolEffect.valueOf(rs.getString("effect")), ToolReplay.valueOf(rs.getString("replay")),
                rs.getString("arguments_sha256"), State.valueOf(rs.getString("state")), rs.getInt("attempt"),
                DecisionStore.instant(rs, "started_at"), DecisionStore.instant(rs, "finished_at"),
                result != null ? JSON.readTree(result) : null, DecisionStore.instant(rs, "run_ended_at"));
    }
}
