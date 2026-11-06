package com.flowtivo.tinfirm.agent.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.agent.AgentMemory;

/**
 * SQL access to {@code agent_memory_note} and the memory columns of {@code agent_settings} (V039), under row-level
 * security: the notes of one memory, which is one settings row (ADR-0063).
 */
@Repository
class MemoryStore {

    private static final String COLUMNS = "id, number, state, about, system_id, repository_id, body, replaces_number,"
            + " conversation_id, created_at, updated_at, used_at";

    /** The most rows a tier reads before its budget cuts it. */
    private static final int TIER_ROWS = 50;

    private final JdbcClient jdbc;

    MemoryStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A note's row: the note and its row id. */
    record NoteRow(UUID id, AgentMemory.Note note) {
    }

    /** The memory columns of a settings row. */
    record MemoryRow(UUID settingsId, int nextNumber, @Nullable Instant changedAt, @Nullable Instant compactedAt) {
    }

    /** How full a memory is: its active notes and their characters, and its proposals. */
    record Totals(int active, int characters, int proposed) {
    }

    /** The settings row's memory, locked: every write of a memory takes this lock first. */
    Optional<MemoryRow> lock(UUID companyId, UUID settingsId) {
        return jdbc.sql("""
                SELECT id, memory_next_number, memory_changed_at, memory_compacted_at FROM agent_settings
                 WHERE company_id = ? AND id = ? FOR UPDATE""")
            .params(companyId, settingsId)
            .query((rs, row) -> new MemoryRow(rs.getObject("id", UUID.class), rs.getInt("memory_next_number"),
                    nullableInstant(rs, "memory_changed_at"), nullableInstant(rs, "memory_compacted_at")))
            .optional();
    }

    Optional<MemoryRow> find(UUID companyId, UUID settingsId) {
        return jdbc.sql("""
                SELECT id, memory_next_number, memory_changed_at, memory_compacted_at FROM agent_settings
                 WHERE company_id = ? AND id = ?""")
            .params(companyId, settingsId)
            .query((rs, row) -> new MemoryRow(rs.getObject("id", UUID.class), rs.getInt("memory_next_number"),
                    nullableInstant(rs, "memory_changed_at"), nullableInstant(rs, "memory_compacted_at")))
            .optional();
    }

    /** Takes the next number of the memory, and records the change. */
    int takeNumber(UUID companyId, UUID settingsId, Instant at) {
        return jdbc.sql("""
                UPDATE agent_settings SET memory_next_number = memory_next_number + 1, memory_changed_at = ?
                 WHERE company_id = ? AND id = ?
                RETURNING memory_next_number - 1""")
            .params(ts(at), companyId, settingsId)
            .query(Integer.class)
            .single();
    }

    void changed(UUID companyId, UUID settingsId, Instant at) {
        jdbc.sql("UPDATE agent_settings SET memory_changed_at = ? WHERE company_id = ? AND id = ?")
            .params(ts(at), companyId, settingsId)
            .update();
    }

    void compacted(UUID companyId, UUID settingsId, Instant at) {
        jdbc.sql("UPDATE agent_settings SET memory_compacted_at = ? WHERE company_id = ? AND id = ?")
            .params(ts(at), companyId, settingsId)
            .update();
    }

    Totals totals(UUID settingsId) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE state = 'ACTIVE') AS active,
                       coalesce(sum(char_length(body)) FILTER (WHERE state = 'ACTIVE'), 0) AS characters,
                       count(*) FILTER (WHERE state = 'PROPOSED') AS proposed
                  FROM agent_memory_note WHERE settings_id = ?""")
            .param(settingsId)
            .query((rs, row) -> new Totals(rs.getInt("active"), rs.getInt("characters"), rs.getInt("proposed")))
            .single();
    }

    Optional<NoteRow> byNumber(UUID settingsId, int number) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_memory_note WHERE settings_id = ? AND number = ?")
            .params(settingsId, number)
            .query(MemoryStore::row)
            .optional();
    }

    /** An active note about the same with the same words, ignoring case. */
    Optional<NoteRow> sameActive(UUID settingsId, AgentMemory.About about, String body) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_memory_note WHERE settings_id = ? AND state = 'ACTIVE'"
                + " AND about = ? AND system_id IS NOT DISTINCT FROM ? AND repository_id IS NOT DISTINCT FROM ?"
                + " AND lower(body) = lower(?) LIMIT 1")
            .params(settingsId, kind(about), systemId(about), repositoryId(about), body)
            .query(MemoryStore::row)
            .optional();
    }

    /** Every note of the memory, by number. */
    List<NoteRow> all(UUID settingsId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_memory_note WHERE settings_id = ? ORDER BY number")
            .param(settingsId)
            .query(MemoryStore::row)
            .list();
    }

    /** The active notes of the memory, by number. */
    List<NoteRow> active(UUID settingsId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_memory_note WHERE settings_id = ? AND state = 'ACTIVE'"
                + " ORDER BY number")
            .param(settingsId)
            .query(MemoryStore::row)
            .list();
    }

    void insert(UUID id, UUID companyId, UUID settingsId, int number, AgentMemory.State state,
            AgentMemory.About about, String body, @Nullable Integer replaces, @Nullable UUID conversationId,
            Instant createdAt, Instant usedAt) {
        jdbc.sql("""
                INSERT INTO agent_memory_note (id, company_id, settings_id, number, state, about, system_id,
                                               repository_id, body, replaces_number, conversation_id, created_at,
                                               updated_at, used_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(id, companyId, settingsId, number, state.name(), kind(about), systemId(about),
                    repositoryId(about), body, replaces, conversationId, ts(createdAt), ts(createdAt), ts(usedAt))
            .update();
    }

    /** A note saved again in place: its words, what it is about and where. */
    void replace(UUID settingsId, int number, AgentMemory.About about, String body, @Nullable UUID conversationId,
            Instant at) {
        jdbc.sql("""
                UPDATE agent_memory_note
                   SET about = ?, system_id = ?, repository_id = ?, body = ?, conversation_id = ?, updated_at = ?,
                       used_at = ?
                 WHERE settings_id = ? AND number = ?""")
            .params(kind(about), systemId(about), repositoryId(about), body, conversationId, ts(at), ts(at),
                    settingsId, number)
            .update();
    }

    /** A proposal becomes active. */
    void activate(UUID settingsId, int number, Instant at) {
        jdbc.sql("""
                UPDATE agent_memory_note SET state = 'ACTIVE', replaces_number = NULL, updated_at = ?, used_at = ?
                 WHERE settings_id = ? AND number = ? AND state = 'PROPOSED'""")
            .params(ts(at), ts(at), settingsId, number)
            .update();
    }

    boolean delete(UUID settingsId, int number) {
        return jdbc.sql("DELETE FROM agent_memory_note WHERE settings_id = ? AND number = ?")
            .params(settingsId, number)
            .update() > 0;
    }

    void deleteIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM agent_memory_note WHERE id = ANY(?)")
            .param(ids.toArray(UUID[]::new))
            .update();
    }

    int deleteAll(UUID settingsId) {
        return jdbc.sql("DELETE FROM agent_memory_note WHERE settings_id = ?").param(settingsId).update();
    }

    /** Marks notes used now. */
    void touch(Collection<UUID> ids, Instant at) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE agent_memory_note SET used_at = greatest(used_at, ?) WHERE id = ANY(?)")
            .params(ts(at), ids.toArray(UUID[]::new))
            .update();
    }

    /** A note a compaction kept or made: its last use and first save from its notes, where they were learned. */
    void merged(UUID id, Instant usedAt, Instant createdAt, Collection<UUID> learnedIn) {
        jdbc.sql("""
                UPDATE agent_memory_note SET used_at = ?, created_at = least(created_at, ?),
                       learned_in = ARRAY(SELECT DISTINCT unnest(learned_in || CAST(? AS uuid[])))
                 WHERE id = ?""")
            .params(ts(usedAt), ts(createdAt), learnedIn.toArray(UUID[]::new), id)
            .update();
    }

    /** Where the notes were learned: their conversations, and those of the notes merged into them. */
    List<UUID> learnedIn(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                SELECT DISTINCT c FROM agent_memory_note n,
                       unnest(n.learned_in || CASE WHEN n.conversation_id IS NULL THEN CAST('{}' AS uuid[])
                                                   ELSE ARRAY[n.conversation_id] END) AS c
                 WHERE n.id = ANY(?)""")
            .param(ids.toArray(UUID[]::new))
            .query(UUID.class)
            .list();
    }

    // ---- the tiers -------------------------------------------------------------------------------------------------

    /** Active notes about the owner in general, most recently used first. */
    List<NoteRow> general(UUID settingsId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_memory_note WHERE settings_id = ? AND state = 'ACTIVE'"
                + " AND about = 'GENERAL' ORDER BY used_at DESC, number DESC LIMIT " + TIER_ROWS)
            .param(settingsId)
            .query(MemoryStore::row)
            .list();
    }

    /** Active notes about the repositories, most recently used first. */
    List<NoteRow> aboutRepositories(UUID settingsId, Collection<UUID> repositoryIds) {
        if (repositoryIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_memory_note WHERE settings_id = ? AND state = 'ACTIVE'"
                + " AND about = 'REPOSITORY' AND repository_id = ANY(?) ORDER BY used_at DESC, number DESC LIMIT "
                + TIER_ROWS * 3)
            .params(settingsId, repositoryIds.toArray(UUID[]::new))
            .query(MemoryStore::row)
            .list();
    }

    /** Active notes about a system, most recently used first. */
    List<NoteRow> aboutSystem(UUID settingsId, UUID systemId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_memory_note WHERE settings_id = ? AND state = 'ACTIVE'"
                + " AND about = 'SYSTEM' AND system_id = ? ORDER BY used_at DESC, number DESC LIMIT " + TIER_ROWS)
            .params(settingsId, systemId)
            .query(MemoryStore::row)
            .list();
    }

    /** Active notes whose words match the query ({@code to_tsquery} of the 'simple' configuration), best first. */
    List<NoteRow> search(UUID settingsId, String tsquery, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent_memory_note, to_tsquery('simple', ?) AS q"
                + " WHERE settings_id = ? AND state = 'ACTIVE' AND search @@ q"
                + " ORDER BY ts_rank(search, q) DESC, used_at DESC, number DESC LIMIT ?")
            .params(tsquery, settingsId, limit)
            .query(MemoryStore::row)
            .list();
    }

    /** How many notes the memory has, active and proposed. */
    int[] counts(UUID settingsId) {
        return jdbc.sql("""
                SELECT count(*) FILTER (WHERE state = 'ACTIVE'), count(*) FILTER (WHERE state = 'PROPOSED')
                  FROM agent_memory_note WHERE settings_id = ?""")
            .param(settingsId)
            .query((rs, row) -> new int[] { rs.getInt(1), rs.getInt(2) })
            .single();
    }

    // ---- reviews, retention, erasure, compaction --------------------------------------------------------------------

    /** The triggers whose memory has proposals, with how many and the oldest's time. */
    Map<UUID, AgentMemory.Waiting> proposalsOf(UUID companyId, Collection<UUID> triggerIds) {
        Map<UUID, AgentMemory.Waiting> waiting = new HashMap<>();
        if (triggerIds.isEmpty()) {
            return waiting;
        }
        jdbc.sql("""
                SELECT s.trigger_id, count(*) AS n, min(n.created_at) AS since
                  FROM agent_memory_note n JOIN agent_settings s ON s.id = n.settings_id
                 WHERE n.company_id = ? AND n.state = 'PROPOSED' AND s.trigger_id = ANY(?)
                 GROUP BY s.trigger_id""")
            .params(companyId, triggerIds.toArray(UUID[]::new))
            .query((rs, row) -> waiting.put(rs.getObject("trigger_id", UUID.class),
                    new AgentMemory.Waiting(rs.getInt("n"), instant(rs, "since"))))
            .list();
        return waiting;
    }

    /**
     * Deletes the notes not used since {@code before} (none when null) and the proposals made before
     * {@code proposedBefore}, except those of held people and triggers, and those learned in held conversations.
     */
    long purge(UUID companyId, @Nullable Instant before, Instant proposedBefore, Collection<UUID> heldPrincipals,
            Collection<UUID> heldTriggers, Collection<UUID> heldConversations) {
        return jdbc.sql("DELETE FROM agent_memory_note n USING agent_settings s WHERE s.id = n.settings_id AND "
                + PURGEABLE)
            .params(purgeParams(companyId, before, proposedBefore, heldPrincipals, heldTriggers, heldConversations))
            .update();
    }

    /** How many notes {@link #purge} would delete now, by the same selection. */
    long countPurge(UUID companyId, @Nullable Instant before, Instant proposedBefore, Collection<UUID> heldPrincipals,
            Collection<UUID> heldTriggers, Collection<UUID> heldConversations) {
        return jdbc.sql("SELECT count(*) FROM agent_memory_note n JOIN agent_settings s ON s.id = n.settings_id WHERE "
                + PURGEABLE)
            .params(purgeParams(companyId, before, proposedBefore, heldPrincipals, heldTriggers, heldConversations))
            .query(Long.class)
            .single();
    }

    /** The notes the purge takes, its note {@code n} joined to its settings row {@code s}: {@link #purgeParams}. */
    private static final String PURGEABLE = """
            n.company_id = ?
               AND ((CAST(? AS timestamptz) IS NOT NULL AND n.used_at < CAST(? AS timestamptz))
                    OR (n.state = 'PROPOSED' AND n.created_at < ?))
               AND (s.principal_id IS NULL OR NOT s.principal_id = ANY(?))
               AND (s.trigger_id IS NULL OR NOT s.trigger_id = ANY(?))
               AND (n.conversation_id IS NULL OR NOT n.conversation_id = ANY(?))
               AND NOT n.learned_in && ?""";

    private static Object[] purgeParams(UUID companyId, @Nullable Instant before, Instant proposedBefore,
            Collection<UUID> heldPrincipals, Collection<UUID> heldTriggers, Collection<UUID> heldConversations) {
        return new Object[] { companyId, ts(before), ts(before), ts(proposedBefore),
            heldPrincipals.toArray(UUID[]::new), heldTriggers.toArray(UUID[]::new),
            heldConversations.toArray(UUID[]::new), heldConversations.toArray(UUID[]::new) };
    }

    long eraseLearnedIn(UUID companyId, Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return 0;
        }
        UUID[] ids = conversationIds.toArray(UUID[]::new);
        return jdbc.sql("DELETE FROM agent_memory_note WHERE company_id = ? AND " + LEARNED_IN)
            .params(companyId, ids, ids)
            .update();
    }

    /** How many notes {@link #eraseLearnedIn} would delete. */
    long countLearnedIn(UUID companyId, Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return 0;
        }
        UUID[] ids = conversationIds.toArray(UUID[]::new);
        return jdbc.sql("SELECT count(*) FROM agent_memory_note WHERE company_id = ? AND " + LEARNED_IN)
            .params(companyId, ids, ids)
            .query(Long.class)
            .single();
    }

    /** A note learned in one of the conversations, or merged from one that was: the erasure's and its count's. */
    private static final String LEARNED_IN = "(conversation_id = ANY(?) OR learned_in && ?)";

    /** A memory a compaction may take: its settings row and agent. */
    record Due(UUID settingsId, UUID agentId, @Nullable UUID principalId, @Nullable UUID triggerId) {
    }

    /**
     * The company's memories that changed since their last compaction, which was at or before {@code compactedBefore}
     * (or never was), oldest change first.
     */
    List<Due> due(UUID companyId, Instant compactedBefore, int limit) {
        return jdbc.sql("""
                SELECT id, agent_id, principal_id, trigger_id FROM agent_settings
                 WHERE company_id = ? AND memory_changed_at IS NOT NULL
                   AND (memory_compacted_at IS NULL
                        OR (memory_compacted_at <= ? AND memory_changed_at > memory_compacted_at))
                 ORDER BY memory_changed_at LIMIT ?""")
            .params(companyId, ts(compactedBefore), limit)
            .query((rs, row) -> new Due(rs.getObject("id", UUID.class), rs.getObject("agent_id", UUID.class),
                    rs.getObject("principal_id", UUID.class), rs.getObject("trigger_id", UUID.class)))
            .list();
    }

    // ---- rows ------------------------------------------------------------------------------------------------------

    static String kind(AgentMemory.About about) {
        return switch (about) {
            case AgentMemory.About.General general -> "GENERAL";
            case AgentMemory.About.System system -> "SYSTEM";
            case AgentMemory.About.Repository repository -> "REPOSITORY";
        };
    }

    private static @Nullable UUID systemId(AgentMemory.About about) {
        return about instanceof AgentMemory.About.System system ? system.systemId() : null;
    }

    private static @Nullable UUID repositoryId(AgentMemory.About about) {
        return about instanceof AgentMemory.About.Repository repository ? repository.repositoryId() : null;
    }

    private static NoteRow row(ResultSet rs, int rowNum) throws SQLException {
        AgentMemory.About about = switch (rs.getString("about")) {
            case "SYSTEM" -> new AgentMemory.About.System(rs.getObject("system_id", UUID.class));
            case "REPOSITORY" -> new AgentMemory.About.Repository(rs.getObject("repository_id", UUID.class));
            default -> AgentMemory.About.GENERAL;
        };
        return new NoteRow(rs.getObject("id", UUID.class), new AgentMemory.Note(rs.getInt("number"),
                AgentMemory.State.valueOf(rs.getString("state")), about, rs.getString("body"),
                rs.getObject("replaces_number", Integer.class), rs.getObject("conversation_id", UUID.class),
                instant(rs, "created_at"), instant(rs, "updated_at"), instant(rs, "used_at")));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
