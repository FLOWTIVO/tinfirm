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
 * {@link IdentityContent} for the conversations held in a chat's threads (package 5; the package 6 contract, §5.9, open
 * point 17): a direct message's or a channel's conversation ({@code place} {@code CHAT_THREAD}, its
 * {@code place_ref}'s {@code systemId} the chat system), a channel trigger's included. The identity is the chat user's
 * id there, the {@code id} of a message's {@code external_author} ({@code {id, name, via}}) and a press's
 * {@code decided_by_external_id}. The chat module's own rows ({@code chat_user}, {@code chat_event},
 * {@code chat_link_request}) are its own implementation's.
 * <p>
 * An erasure, in open conversations too: an {@code EXTERNAL} message of theirs (a channel's mention) loses its body as
 * a purge does (the removal marker, {@code purged_at}) and its author with it ({@code external_author} becomes
 * {@code {}}); a linked member's {@code PERSON} message keeps its body, the member's, which the person's own erasure
 * reaches, and loses the chat's name for them; a question their message answered loses its texts as a purge does and
 * their name; a decision they pressed loses their id and name there, its texts (an option the agent offered) staying;
 * a conversation their message started loses its title, which was made from it. The runs whose prompts may have quoted
 * the words are the conversation's runs from their first message on, whose events the caller purges. Runs in the
 * caller's transaction and tenant context, which locked the conversations {@link #conversationsOf} named. Its count
 * selects the same messages, decisions, titles and runs, changing nothing.
 */
@Component
class ChatThreadIdentityContent implements IdentityContent {

    private static final String THREADS = """
            JOIN conversation c ON c.id = m.conversation_id AND c.company_id = m.company_id
           WHERE c.company_id = ? AND c.place = 'CHAT_THREAD' AND c.place_ref ->> 'systemId' = ?""";

    private static final String WORDS = " FROM message m " + THREADS
            + " AND m.kind = 'EXTERNAL' AND m.external_author ->> 'id' = ?";

    private static final String NAMED = " FROM message m " + THREADS
            + " AND m.kind = 'PERSON' AND m.external_author ->> 'id' = ?";

    private static final String PRESSED = """
             FROM decision d
             JOIN conversation c ON c.id = d.conversation_id AND c.company_id = d.company_id
            WHERE c.company_id = ? AND c.place = 'CHAT_THREAD' AND c.place_ref ->> 'systemId' = ?
              AND d.decided_by_external_id = ?""";

    /**
     * The conversations their message started that keep the title made from it; parameters: the conversations their
     * messages are in, their messages.
     */
    private static final String TITLED = """
            c.id = ANY (CAST(? AS uuid[])) AND c.title_purged_at IS NULL
               AND (SELECT f.id FROM message f WHERE f.conversation_id = c.id
                     ORDER BY f.created_at, f.id LIMIT 1) = ANY (CAST(? AS uuid[]))""";

    /** The questions their messages answered that keep their texts; parameter: their messages. */
    private static final String ANSWERED_KEPT = "decided_by_message_id = ANY (CAST(? AS uuid[])) AND purged_at IS NULL";

    /**
     * The decisions their messages answered or they pressed that keep their name or id; parameters: their messages,
     * their presses.
     */
    private static final String ANSWERED_NAMED = "(decided_by_message_id = ANY (CAST(? AS uuid[]))"
            + " OR id = ANY (CAST(? AS uuid[])))"
            + " AND (decided_by_name IS NOT NULL OR decided_by_external_id IS NOT NULL)";

    private final JdbcClient jdbc;

    ChatThreadIdentityContent(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Set<UUID> conversationsOf(UUID companyId, UUID systemId, String externalId) {
        Object[] params = {companyId, systemId.toString(), externalId};
        Set<UUID> conversations = new HashSet<>(jdbc.sql("SELECT DISTINCT m.conversation_id" + WORDS)
            .params(params)
            .query(UUID.class)
            .list());
        conversations.addAll(jdbc.sql("SELECT DISTINCT m.conversation_id" + NAMED)
            .params(params)
            .query(UUID.class)
            .list());
        conversations.addAll(jdbc.sql("SELECT DISTINCT d.conversation_id" + PRESSED)
            .params(params)
            .query(UUID.class)
            .list());
        return conversations;
    }

    @Override
    public Erased erase(UUID companyId, UUID systemId, String externalId, Instant at) {
        Reached reached = reached(companyId, systemId, externalId);
        if (reached.none()) {
            return new Erased(Map.of(), Set.of());
        }
        long titles = jdbc.sql("UPDATE conversation c SET title = NULL, title_purged_at = ? WHERE " + TITLED)
            .params(ts(at), reached.conversationIds(), reached.messageIds())
            .update();
        long decisions = jdbc.sql("UPDATE decision SET decided_by_name = NULL, decided_by_external_id = NULL, "
                + ConversationPurgeImpl.DECISION_TEXTS + " WHERE " + ANSWERED_KEPT)
            .params(ts(at), reached.messageIds())
            .update();
        decisions += jdbc.sql("UPDATE decision SET decided_by_name = NULL, decided_by_external_id = NULL WHERE "
                + ANSWERED_NAMED)
            .params(reached.messageIds(), reached.pressedIds())
            .update();
        long messages = jdbc.sql("""
                UPDATE message SET body = NULL, purged_at = coalesce(purged_at, ?), external_author = '{}'::jsonb
                 WHERE id = ANY (CAST(? AS uuid[]))""")
            .params(ts(at), reached.messageIds())
            .update();
        long authors = jdbc.sql("UPDATE message SET external_author = '{}'::jsonb WHERE id = ANY (CAST(? AS uuid[]))")
            .params((Object) reached.namedIds())
            .update();
        return erased(messages, decisions, authors, titles, runsQuoting(companyId, reached.said()));
    }

    @Override
    public Erased count(UUID companyId, UUID systemId, String externalId) {
        Reached reached = reached(companyId, systemId, externalId);
        if (reached.none()) {
            return new Erased(Map.of(), Set.of());
        }
        long titles = jdbc.sql("SELECT count(*) FROM conversation c WHERE " + TITLED)
            .params(reached.conversationIds(), reached.messageIds())
            .query(Long.class)
            .single();
        // The erasure's first update clears the name and the id of the questions whose texts it takes, so its second
        // takes only the others: counted apart.
        long decisions = jdbc.sql("SELECT count(*) FROM decision WHERE " + ANSWERED_KEPT)
            .params((Object) reached.messageIds())
            .query(Long.class)
            .single();
        decisions += jdbc.sql("SELECT count(*) FROM decision WHERE " + ANSWERED_NAMED + " AND (" + ANSWERED_KEPT
                + ") IS NOT TRUE")
            .params(reached.messageIds(), reached.pressedIds(), reached.messageIds())
            .query(Long.class)
            .single();
        // Their messages and the linked member's: the erasure changes each of them.
        return erased(reached.said().size(), decisions, reached.namedIds().length, titles,
                runsQuoting(companyId, reached.said()));
    }

    /** What of theirs the chat's threads hold: their messages, a linked member's messages named for them, presses. */
    private Reached reached(UUID companyId, UUID systemId, String externalId) {
        Object[] params = {companyId, systemId.toString(), externalId};
        List<Said> said = jdbc.sql("SELECT m.id, m.conversation_id, m.created_at" + WORDS)
            .params(params)
            .query((rs, row) -> new Said(rs.getObject("id", UUID.class), rs.getObject("conversation_id", UUID.class),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
            .list();
        List<UUID> named = jdbc.sql("SELECT m.id" + NAMED).params(params).query(UUID.class).list();
        List<UUID> pressed = jdbc.sql("SELECT d.id" + PRESSED).params(params).query(UUID.class).list();
        return new Reached(said, named, pressed);
    }

    /** The runs whose prompts may have quoted their messages: each conversation's from their first message on. */
    private Set<UUID> runsQuoting(UUID companyId, List<Said> said) {
        Map<UUID, Instant> first = new LinkedHashMap<>();
        said.forEach(message -> first.merge(message.conversationId(), message.createdAt(),
                (a, b) -> a.isBefore(b) ? a : b));
        Set<UUID> runs = new HashSet<>();
        first.forEach((conversationId, since) -> runs.addAll(jdbc.sql("""
                SELECT id FROM run WHERE company_id = ? AND conversation_id = ? AND created_at >= ?""")
            .params(companyId, conversationId, ts(since))
            .query(UUID.class)
            .list()));
        return runs;
    }

    private static Erased erased(long messages, long decisions, long authors, long titles, Set<UUID> runs) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("messages", messages);
        counts.put("decisions", decisions);
        if (authors > 0) {
            counts.put("messageAuthors", authors);
        }
        if (titles > 0) {
            counts.put("titles", titles);
        }
        return new Erased(counts, runs);
    }

    /** A chat user's message in a chat thread's conversation. */
    private record Said(UUID id, UUID conversationId, Instant createdAt) {
    }

    /** What of a chat user's the chat's threads hold. */
    private record Reached(List<Said> said, List<UUID> named, List<UUID> pressed) {

        boolean none() {
            return said.isEmpty() && named.isEmpty() && pressed.isEmpty();
        }

        String[] messageIds() {
            return said.stream().map(message -> message.id().toString()).toArray(String[]::new);
        }

        /** The conversations their messages are in, whose title one of them may have made. */
        String[] conversationIds() {
            return said.stream().map(message -> message.conversationId().toString()).distinct()
                .toArray(String[]::new);
        }

        String[] namedIds() {
            return named.stream().map(UUID::toString).toArray(String[]::new);
        }

        String[] pressedIds() {
            return pressed.stream().map(UUID::toString).toArray(String[]::new);
        }
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
