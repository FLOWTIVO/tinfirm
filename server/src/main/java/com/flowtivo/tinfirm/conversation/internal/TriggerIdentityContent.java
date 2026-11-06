package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.retention.IdentityContent;

/**
 * {@link IdentityContent} for the conversations of triggers (package 4; the package 6 contract, §5.9, open point 17):
 * what someone outside Tinfirm wrote on a trigger's issue, kept as {@code EXTERNAL} messages with their
 * {@code external_author} ({@code {id, login, name}} at the system), and the questions that such a comment answered
 * ({@code decided_by_name}, {@code decided_by_message_id}). The identity is its {@code id} at the trigger's source system,
 * which is the conversation's subject's system. Only trigger's conversations outside a chat: those held in a chat's
 * thread, a channel trigger's among them, are {@code ChatThreadIdentityContent}'s.
 * <p>
 * An erasure, in open conversations too: the comment's body goes as a purge does (the removal marker, {@code purged_at})
 * and its author's {@code id}, login and name with it ({@code external_author} becomes {@code {}}); a question it
 * answered loses its texts as a purge does and the commenter's name. The runs whose prompts may have quoted the words are
 * the conversation's runs from the first such comment on, whose events the caller purges. Runs in the caller's
 * transaction and tenant context, which locked the conversations {@link #conversationsOf} named. Its count selects the
 * same comments, decisions and runs, changing nothing.
 */
@Component
class TriggerIdentityContent implements IdentityContent {

    private static final String WORDS = """
            FROM message m
            JOIN conversation c ON c.id = m.conversation_id AND c.company_id = m.company_id
           WHERE c.company_id = ? AND c.trigger_id IS NOT NULL AND c.subject_system_id = ?
             AND c.place <> 'CHAT_THREAD' AND m.kind = 'EXTERNAL' AND m.external_author ->> 'id' = ?""";

    /** The questions the comments answered that keep their texts; parameter: the comments. */
    private static final String ANSWERED_KEPT = "decided_by_message_id = ANY (CAST(? AS uuid[])) AND purged_at IS NULL";

    /** The questions the comments answered, purged already, that keep the commenter's name; likewise. */
    private static final String ANSWERED_NAMED = "decided_by_message_id = ANY (CAST(? AS uuid[]))"
            + " AND purged_at IS NOT NULL AND decided_by_name IS NOT NULL";

    private final JdbcClient jdbc;

    TriggerIdentityContent(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<UUID> conversationsOf(UUID companyId, UUID systemId, String externalId) {
        return new HashSet<>(jdbc.sql("SELECT DISTINCT m.conversation_id " + WORDS)
            .params(companyId, systemId, externalId)
            .query(UUID.class)
            .list());
    }

    @Override
    public Erased erase(UUID companyId, UUID systemId, String externalId, Instant at) {
        List<Comment> comments = comments(companyId, systemId, externalId);
        if (comments.isEmpty()) {
            return new Erased(Map.of(), Set.of());
        }
        String[] messageIds = messageIds(comments);
        long decisions = jdbc.sql("UPDATE decision SET decided_by_name = NULL, " + ConversationPurgeImpl.DECISION_TEXTS
                + " WHERE " + ANSWERED_KEPT)
            .params(ts(at), messageIds)
            .update();
        decisions += jdbc.sql("UPDATE decision SET decided_by_name = NULL WHERE " + ANSWERED_NAMED)
            .params((Object) messageIds)
            .update();
        long messages = jdbc.sql("""
                UPDATE message SET body = NULL, purged_at = coalesce(purged_at, ?), external_author = '{}'::jsonb
                 WHERE id = ANY (CAST(? AS uuid[]))""")
            .params(ts(at), messageIds)
            .update();
        return erased(messages, decisions, runsQuoting(companyId, comments));
    }

    @Override
    public Erased count(UUID companyId, UUID systemId, String externalId) {
        List<Comment> comments = comments(companyId, systemId, externalId);
        if (comments.isEmpty()) {
            return new Erased(Map.of(), Set.of());
        }
        String[] messageIds = messageIds(comments);
        // The two updates of the decisions take disjoint rows (texts kept, or purged already with the name kept).
        long decisions = count("SELECT count(*) FROM decision WHERE " + ANSWERED_KEPT, messageIds)
                + count("SELECT count(*) FROM decision WHERE " + ANSWERED_NAMED, messageIds);
        // The comments themselves: the erasure changes each of them.
        return erased(comments.size(), decisions, runsQuoting(companyId, comments));
    }

    /** The identity's comments in the trigger's conversations outside a chat. */
    private List<Comment> comments(UUID companyId, UUID systemId, String externalId) {
        return jdbc.sql("SELECT m.id, m.conversation_id, m.created_at " + WORDS)
            .params(companyId, systemId, externalId)
            .query((rs, row) -> new Comment(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
            .list();
    }

    /** The runs whose prompts may have quoted the comments: each conversation's from its first such comment on. */
    private Set<UUID> runsQuoting(UUID companyId, List<Comment> comments) {
        Map<UUID, Instant> first = new LinkedHashMap<>();
        comments.forEach(comment -> first.merge(comment.conversationId(), comment.createdAt(),
                (a, b) -> a.isBefore(b) ? a : b));
        Set<UUID> runs = new HashSet<>();
        first.forEach((conversationId, since) -> runs.addAll(jdbc.sql("""
                SELECT id FROM run WHERE company_id = ? AND conversation_id = ? AND created_at >= ?""")
            .params(companyId, conversationId, ts(since))
            .query(UUID.class)
            .list()));
        return runs;
    }

    private static Erased erased(long messages, long decisions, Set<UUID> runs) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("messages", messages);
        counts.put("decisions", decisions);
        return new Erased(counts, runs);
    }

    private long count(String sql, String[] ids) {
        return jdbc.sql(sql).params((Object) ids).query(Long.class).single();
    }

    private static String[] messageIds(List<Comment> comments) {
        return comments.stream().map(comment -> comment.id().toString()).toArray(String[]::new);
    }

    /** An identity's comment in a trigger's conversation. */
    private record Comment(UUID id, UUID conversationId, Instant createdAt) {
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
