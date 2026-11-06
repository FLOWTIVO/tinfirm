package com.flowtivo.tinfirm.conversation.internal;

import java.sql.Types;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.conversation.ConversationPurge;
import com.flowtivo.tinfirm.run.RunPurge;

/**
 * {@link ConversationPurge} on the conversation module's tables (V023's markers), under row-level security. Each text
 * goes with its row's marker set, which the database's checks pair; the tombstone stays. A decision still open is never
 * purged: only an ended conversation's, or one that was decided or cancelled. Each purge and its count share the
 * selection of the conversations ({@link #dueWhere}, {@link #ENDED_UNHELD}) and the predicate of the rows they take
 * ({@link #MESSAGE_KEPT} and its neighbours), so that a preview counts what the purge removes.
 */
@Component
class ConversationPurgeImpl implements ConversationPurge {

    private static final String CHAT = "c.place IN ('CHAT_THREAD', 'WEBHOOK')";

    /**
     * The principal a hold on a person reaches the conversation through: its person, or a trigger's conversation's
     * author, for whom it acts (it has no person; the package 4 contract, §3.4).
     */
    private static final String HELD_THROUGH = "coalesce(c.person_principal_id, c.author_principal_id)";

    /** The conversation is not held, as a conversation or through its person (a trigger's: its author). */
    private static final String NOT_HELD = " AND NOT c.id = ANY (CAST(? AS uuid[]))"
            + " AND NOT " + HELD_THROUGH + " = ANY (CAST(? AS uuid[]))";

    /** An ended conversation no hold covers; parameters: the held conversations, the held principals. */
    private static final String ENDED_UNHELD = "c.state = 'ENDED'" + NOT_HELD;

    private static final String MESSAGES_KEPT = "EXISTS (SELECT 1 FROM message m WHERE m.conversation_id = c.id"
            + " AND m.purged_at IS NULL)";

    private static final String DECISIONS_KEPT = "EXISTS (SELECT 1 FROM decision d WHERE d.conversation_id = c.id"
            + " AND d.purged_at IS NULL AND d.status <> 'OPEN')";

    private static final String OUTPUTS_KEPT = "EXISTS (SELECT 1 FROM output o WHERE o.conversation_id = c.id"
            + " AND o.purged_at IS NULL)";

    private static final String FILES_KEPT = "EXISTS (SELECT 1 FROM attachment a WHERE a.conversation_id = c.id"
            + " AND a.content_purged_at IS NULL)";

    /** A message whose body is kept, outside what a held person said; parameter: the held principals. */
    private static final String MESSAGE_KEPT = "purged_at IS NULL"
            + " AND (author_principal_id IS NULL OR NOT author_principal_id = ANY (CAST(? AS uuid[])))";

    /** A decided or cancelled decision whose texts are kept, outside what a held person decided; likewise. */
    private static final String DECISION_KEPT = "purged_at IS NULL AND status <> 'OPEN'"
            + " AND (decided_by_principal_id IS NULL OR NOT decided_by_principal_id = ANY (CAST(? AS uuid[])))";

    /** An output whose title is kept. */
    private static final String OUTPUT_KEPT = "purged_at IS NULL";

    /** A file whose content is kept, outside what a held person added; likewise. */
    private static final String FILE_KEPT = "content_purged_at IS NULL"
            + " AND (uploaded_by_user_id IS NULL OR uploaded_by_user_id NOT IN ("
            + "SELECT p.user_id FROM principal p WHERE p.id = ANY (CAST(? AS uuid[])) AND p.user_id IS NOT NULL))";

    /** Rows of these conversations; parameter: their ids. */
    private static final String OF_CONVERSATIONS = "conversation_id = ANY (CAST(? AS uuid[]))";

    /**
     * What the person said in conversations that are not theirs, outside the excluded ones; parameters: the person,
     * the person, the excluded conversations.
     */
    private static final String AUTHORED_MESSAGES = "c.id = m.conversation_id"
            + " AND c.person_principal_id IS DISTINCT FROM ? AND m.author_principal_id = ? AND m.purged_at IS NULL"
            + " AND NOT m.conversation_id = ANY (CAST(? AS uuid[]))";

    /** What the person decided there; likewise. */
    private static final String AUTHORED_DECISIONS = "c.id = d.conversation_id"
            + " AND c.person_principal_id IS DISTINCT FROM ? AND d.decided_by_principal_id = ? AND d.purged_at IS NULL"
            + " AND d.status <> 'OPEN' AND NOT d.conversation_id = ANY (CAST(? AS uuid[]))";

    /** The files the person's user added there; parameters: the person, their user, the excluded conversations. */
    private static final String AUTHORED_FILES = "c.id = a.conversation_id"
            + " AND c.person_principal_id IS DISTINCT FROM ? AND a.uploaded_by_user_id = ?"
            + " AND a.content_purged_at IS NULL AND NOT a.conversation_id = ANY (CAST(? AS uuid[]))";

    private final JdbcClient jdbc;

    private final ToolCallStore toolCalls;

    private final InboxDismissalStore dismissals;

    ConversationPurgeImpl(JdbcClient jdbc, ToolCallStore toolCalls, InboxDismissalStore dismissals) {
        this.jdbc = jdbc;
        this.toolCalls = toolCalls;
        this.dismissals = dismissals;
    }

    @Override
    public long eraseDismissals(UUID principalId) {
        return dismissals.forget(principalId);
    }

    @Override
    public long countDismissals(UUID principalId) {
        return dismissals.count(principalId);
    }

    @Override
    public List<Due> due(Kind kind, Instant endedBefore, Held held, @Nullable Due after, int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT c.id, c.ended_at FROM conversation c WHERE ")
            .append(dueWhere(kind, endedBefore, held, params));
        if (after != null) {
            sql.append(" AND (c.ended_at, c.id) > (?, ?)");
            params.add(ts(after.endedAt()));
            params.add(after.conversationId());
        }
        sql.append(" ORDER BY c.ended_at, c.id LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString())
            .params(params)
            .query((rs, row) -> new Due(rs.getObject("id", UUID.class),
                    rs.getObject("ended_at", OffsetDateTime.class).toInstant()))
            .list();
    }

    @Override
    public RunPurge.ConversationSelection dueSelection(Kind kind, Instant endedBefore, Held held) {
        List<Object> params = new ArrayList<>();
        String where = dueWhere(kind, endedBefore, held, params);
        return new RunPurge.ConversationSelection("SELECT c.id FROM conversation c WHERE " + where, params);
    }

    @Override
    public Purged countSweep(Instant chatBefore, @Nullable Instant contentBefore, @Nullable Instant filesBefore,
            Held held) {
        String[] heldPeople = ids(held.principalIds());
        // The texts go under CHAT (a chat's or a webhook's) and under CONTENT (every conversation's), each row once:
        // the sweep's CHAT pass runs first, and its CONTENT pass finds nothing left of what CHAT took.
        RunPurge.ConversationSelection chat = dueSelection(Kind.CHAT, chatBefore, held);
        String texts = chat.sql();
        List<Object> textParams = new ArrayList<>(chat.params());
        long conversations = 0;
        long outputs = 0;
        if (contentBefore != null) {
            RunPurge.ConversationSelection content = dueSelection(Kind.CONTENT, contentBefore, held);
            texts = texts + " UNION ALL " + content.sql();
            textParams.addAll(content.params());
            conversations = count("SELECT count(*) FROM conversation WHERE id IN (" + content.sql() + ") AND "
                    + markerKept(false), content.params());
            outputs = countIn("output", content.sql(), content.params(), OUTPUT_KEPT);
        }
        long messages = countIn("message", texts, textParams, MESSAGE_KEPT, (Object) heldPeople);
        long decisions = countIn("decision", texts, textParams, DECISION_KEPT, (Object) heldPeople);
        long files = 0;
        if (filesBefore != null) {
            RunPurge.ConversationSelection due = dueSelection(Kind.FILES, filesBefore, held);
            files = countIn("attachment", due.sql(), due.params(), FILE_KEPT, (Object) heldPeople);
        }
        return new Purged(conversations, messages, decisions, outputs, files);
    }

    /**
     * The sweep's selection of the conversations due for a kind: ended before the cutoff, no hold covering them, and
     * still holding something of the kind ({@code CHAT}: only the chats' and webhooks' places). Adds its parameters.
     */
    private static String dueWhere(Kind kind, Instant endedBefore, Held held, List<Object> params) {
        params.add(ids(held.conversationIds()));
        params.add(ids(held.principalIds()));
        params.add(ts(endedBefore));
        String what = switch (kind) {
            case CHAT -> CHAT + " AND (" + MESSAGES_KEPT + " OR " + DECISIONS_KEPT + ")";
            case CONTENT -> "(c.content_purged_at IS NULL OR " + MESSAGES_KEPT + " OR " + DECISIONS_KEPT + " OR "
                    + OUTPUTS_KEPT + ")";
            case FILES -> FILES_KEPT;
        };
        return ENDED_UNHELD + " AND c.ended_at < ? AND " + what;
    }

    @Override
    public Set<UUID> endedUnheld(Collection<UUID> conversationIds, Held held) {
        if (conversationIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("SELECT c.id FROM conversation c WHERE c.id = ANY (CAST(? AS uuid[])) AND "
                + ENDED_UNHELD)
            .params(ids(conversationIds), ids(held.conversationIds()), ids(held.principalIds()))
            .query(UUID.class)
            .list());
    }

    @Override
    public RunPurge.ConversationSelection endedUnheldSelection(Held held) {
        return new RunPurge.ConversationSelection("SELECT c.id FROM conversation c WHERE " + ENDED_UNHELD,
                List.<Object>of(ids(held.conversationIds()), ids(held.principalIds())));
    }

    @Override
    public Set<UUID> covered(Collection<UUID> conversationIds, Held held) {
        if (conversationIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                SELECT c.id FROM conversation c
                 WHERE c.id = ANY (CAST(? AS uuid[]))
                   AND (c.id = ANY (CAST(? AS uuid[]))
                        OR coalesce(c.person_principal_id, c.author_principal_id) = ANY (CAST(? AS uuid[])))""")
            .params(ids(conversationIds), ids(held.conversationIds()), ids(held.principalIds()))
            .query(UUID.class)
            .list());
    }

    @Override
    public void lock(Collection<UUID> conversationIds) {
        if (!conversationIds.isEmpty()) {
            jdbc.sql("SELECT id FROM conversation WHERE id = ANY (CAST(? AS uuid[])) ORDER BY id FOR NO KEY UPDATE")
                .params((Object) ids(conversationIds))
                .query(UUID.class)
                .list();
        }
    }

    @Override
    public Purged purge(UUID conversationId, Kind kind, Held held, Instant at) {
        String[] heldPeople = ids(held.principalIds());
        return switch (kind) {
            case CHAT -> new Purged(0, messages(conversationId, heldPeople, at), decisions(conversationId, heldPeople,
                    at), 0, 0);
            case CONTENT -> {
                toolCalls.deleteOfConversation(conversationId);
                long messages = messages(conversationId, heldPeople, at);
                long decisions = decisions(conversationId, heldPeople, at);
                long outputs = outputs(conversationId, at);
                long conversations = conversation(conversationId, at, false);
                yield new Purged(conversations, messages, decisions, outputs, 0);
            }
            case FILES -> new Purged(0, 0, 0, 0, files(conversationId, heldPeople, at));
        };
    }

    @Override
    public Purged erase(UUID conversationId, Instant at) {
        toolCalls.deleteOfConversation(conversationId);
        String[] none = new String[0];
        long messages = messages(conversationId, none, at);
        long decisions = decisions(conversationId, none, at);
        long outputs = outputs(conversationId, at);
        long files = files(conversationId, none, at);
        long conversations = conversation(conversationId, at, true);
        return new Purged(conversations, messages, decisions, outputs, files);
    }

    @Override
    public Purged countErase(Collection<UUID> conversationIds) {
        if (conversationIds.isEmpty()) {
            return Purged.NONE;
        }
        String[] of = ids(conversationIds);
        // An erasure holds no one back: the held principals are none.
        String[] none = new String[0];
        long messages = count("SELECT count(*) FROM message WHERE " + OF_CONVERSATIONS + " AND " + MESSAGE_KEPT,
                List.of(of, none));
        long decisions = count("SELECT count(*) FROM decision WHERE " + OF_CONVERSATIONS + " AND " + DECISION_KEPT,
                List.of(of, none));
        long outputs = count("SELECT count(*) FROM output WHERE " + OF_CONVERSATIONS + " AND " + OUTPUT_KEPT,
                List.of((Object) of));
        long files = count("SELECT count(*) FROM attachment WHERE " + OF_CONVERSATIONS + " AND " + FILE_KEPT,
                List.of(of, none));
        long conversations = count("SELECT count(*) FROM conversation WHERE id = ANY (CAST(? AS uuid[])) AND "
                + markerKept(true), List.of((Object) of));
        return new Purged(conversations, messages, decisions, outputs, files);
    }

    @Override
    public int purgeToolCalls(Collection<UUID> runIds) {
        return toolCalls.deleteOfRuns(runIds);
    }

    @Override
    public Purged eraseAuthored(UUID principalId, @Nullable UUID userId, Set<UUID> excludedConversationIds,
            Instant at) {
        String[] excluded = ids(excludedConversationIds);
        long messages = jdbc.sql("UPDATE message m SET body = NULL, purged_at = ? FROM conversation c WHERE "
                + AUTHORED_MESSAGES)
            .params(ts(at), principalId, principalId, excluded)
            .update();
        long decisions = jdbc.sql("UPDATE decision d SET " + DECISION_TEXTS + " FROM conversation c WHERE "
                + AUTHORED_DECISIONS)
            .params(ts(at), principalId, principalId, excluded)
            .update();
        long files = 0;
        if (userId != null) {
            files = jdbc.sql("UPDATE attachment a SET content = NULL, note = NULL, content_purged_at = ?"
                    + " FROM conversation c WHERE " + AUTHORED_FILES)
                .params(ts(at), principalId, userId, excluded)
                .update();
        }
        return new Purged(0, messages, decisions, 0, files);
    }

    @Override
    public Purged countAuthored(UUID principalId, @Nullable UUID userId, Set<UUID> excludedConversationIds) {
        String[] excluded = ids(excludedConversationIds);
        long messages = count("SELECT count(*) FROM message m, conversation c WHERE " + AUTHORED_MESSAGES,
                List.of(principalId, principalId, excluded));
        long decisions = count("SELECT count(*) FROM decision d, conversation c WHERE " + AUTHORED_DECISIONS,
                List.of(principalId, principalId, excluded));
        long files = 0;
        if (userId != null) {
            files = count("SELECT count(*) FROM attachment a, conversation c WHERE " + AUTHORED_FILES,
                    List.of(principalId, userId, excluded));
        }
        return new Purged(0, messages, decisions, 0, files);
    }

    @Override
    public List<Personal> personal(UUID principalId) {
        return jdbc.sql("""
                SELECT id, key, state FROM conversation WHERE person_principal_id = ? ORDER BY created_at, id""")
            .params(principalId)
            .query((rs, row) -> new Personal(rs.getObject("id", UUID.class), rs.getString("key"),
                    "OPEN".equals(rs.getString("state"))))
            .list();
    }

    @Override
    public Set<UUID> authoredIn(UUID principalId, @Nullable UUID userId) {
        return new HashSet<>(jdbc.sql("""
                SELECT c.id FROM conversation c
                 WHERE c.person_principal_id IS DISTINCT FROM ?
                   AND (EXISTS (SELECT 1 FROM message m WHERE m.conversation_id = c.id AND m.author_principal_id = ?
                                   AND m.purged_at IS NULL)
                        OR EXISTS (SELECT 1 FROM decision d WHERE d.conversation_id = c.id
                                      AND d.decided_by_principal_id = ? AND d.purged_at IS NULL)
                        OR EXISTS (SELECT 1 FROM attachment a WHERE a.conversation_id = c.id
                                      AND a.uploaded_by_user_id = ? AND a.content_purged_at IS NULL))""")
            .params(principalId, principalId, principalId, new SqlParameterValue(Types.OTHER, userId))
            .query(UUID.class)
            .list());
    }

    @Override
    public List<Open> openOlder(Instant chatBefore, @Nullable Instant contentBefore, @Nullable Open after,
            int limit) {
        List<Object> params = new ArrayList<>();
        StringBuilder sql = new StringBuilder("SELECT c.id, c.key, c.place, c.created_at FROM conversation c WHERE ")
            .append(openWhere(chatBefore, contentBefore, params));
        if (after != null) {
            sql.append(" AND (c.created_at, c.id) > (?, ?)");
            params.add(ts(after.createdAt()));
            params.add(after.id());
        }
        sql.append(" ORDER BY c.created_at, c.id LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString())
            .params(params)
            .query((rs, row) -> new Open(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("place"),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
            .list();
    }

    @Override
    public long countOpenOlder(Instant chatBefore, @Nullable Instant contentBefore) {
        List<Object> params = new ArrayList<>();
        String where = openWhere(chatBefore, contentBefore, params);
        return count("SELECT count(*) FROM conversation c WHERE " + where, params);
    }

    private static String openWhere(Instant chatBefore, @Nullable Instant contentBefore, List<Object> params) {
        params.add(ts(chatBefore));
        StringBuilder where = new StringBuilder("c.state = 'OPEN' AND ((" + CHAT + " AND c.created_at < ?)");
        if (contentBefore != null) {
            where.append(" OR (NOT ").append(CHAT).append(" AND c.created_at < ?)");
            params.add(ts(contentBefore));
        }
        return where.append(")").toString();
    }

    // ---- the purges of one conversation ----------------------------------------------------------------------------

    /**
     * The texts of a decision, and its subject's: a tool call's arguments and words, a result's summary and writes;
     * the first parameter the time.
     */
    static final String DECISION_TEXTS = "question = NULL, options = NULL, reason = NULL, answer = NULL,"
            + " option = NULL, note = NULL, result = NULL,"
            + " subject = CASE WHEN subject IS NULL THEN NULL"
            + " ELSE subject - 'arguments' - 'what' - 'summary' - 'writes' - 'writeItems' END, purged_at = ?";

    private long messages(UUID conversationId, String[] heldPeople, Instant at) {
        return jdbc.sql("UPDATE message SET body = NULL, purged_at = ? WHERE conversation_id = ? AND " + MESSAGE_KEPT)
            .params(ts(at), conversationId, heldPeople)
            .update();
    }

    private long decisions(UUID conversationId, String[] heldPeople, Instant at) {
        return jdbc.sql("UPDATE decision SET " + DECISION_TEXTS + " WHERE conversation_id = ? AND " + DECISION_KEPT)
            .params(ts(at), conversationId, heldPeople)
            .update();
    }

    private long outputs(UUID conversationId, Instant at) {
        return jdbc.sql("UPDATE output SET title = NULL, purged_at = ? WHERE conversation_id = ? AND " + OUTPUT_KEPT)
            .params(ts(at), conversationId)
            .update();
    }

    private long files(UUID conversationId, String[] heldPeople, Instant at) {
        return jdbc.sql("UPDATE attachment SET content = NULL, note = NULL, content_purged_at = ?"
                + " WHERE conversation_id = ? AND " + FILE_KEPT)
            .params(ts(at), conversationId, heldPeople)
            .update();
    }

    /**
     * The conversation's own texts (its title, the latest turn's result, the subject's title), and its marker for the
     * tombstone: {@code erased_at} for an erasure, else {@code content_purged_at}. Answers 1 when it was not marked so.
     */
    private long conversation(UUID conversationId, Instant at, boolean erased) {
        String marker = erased ? "erased_at" : "content_purged_at";
        return jdbc.sql("UPDATE conversation SET title = NULL, title_purged_at = coalesce(title_purged_at, ?),"
                + " result_summary = NULL,"
                + " result_next_steps = CASE WHEN result_next_steps IS NULL THEN NULL ELSE '[]'::jsonb END,"
                + " result_open_questions = CASE WHEN result_open_questions IS NULL THEN NULL ELSE '[]'::jsonb END,"
                + " subject_title = NULL, " + marker + " = ? WHERE id = ? AND " + markerKept(erased))
            .params(ts(at), ts(at), conversationId)
            .update();
    }

    /** A conversation not marked yet: erased, or its content purged. */
    private static String markerKept(boolean erased) {
        return (erased ? "erased_at" : "content_purged_at") + " IS NULL";
    }

    /**
     * How many rows of the table in the conversations the SQL selects take the predicate: the selection's parameters,
     * then the predicate's.
     */
    private long countIn(String table, String conversations, List<Object> params, String kept, Object... keptParams) {
        List<Object> all = new ArrayList<>(params);
        all.addAll(List.of(keptParams));
        return count("SELECT count(*) FROM " + table + " WHERE conversation_id IN (" + conversations + ") AND " + kept,
                all);
    }

    private long count(String sql, List<?> params) {
        return jdbc.sql(sql).params(params).query(Long.class).single();
    }

    private static String[] ids(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toArray(String[]::new);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
