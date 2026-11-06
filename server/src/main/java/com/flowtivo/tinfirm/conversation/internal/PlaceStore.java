package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.time.TimeSource;

import tools.jackson.databind.JsonNode;

/**
 * SQL of conversations held in a place outside the app (the package 5 contract, §3.9; V018), under row-level security:
 * a message of the place with its external id and author, stored once per conversation (C3); a place's reference;
 * the conversation of a chat thread (C8); how a decision was decided from the place.
 */
@Repository
class PlaceStore {

    private final JdbcClient jdbc;

    private final ConversationStore conversations;

    private final TimeSource time;

    PlaceStore(JdbcClient jdbc, ConversationStore conversations, TimeSource time) {
        this.jdbc = jdbc;
        this.conversations = conversations;
        this.time = time;
    }

    /**
     * A message of the place, unless the conversation holds one of this external id: its id, or empty for a
     * duplicate. It waits for the next turn, as a person's message does; the conversation's activity moves.
     */
    Optional<UUID> insertMessage(UUID companyId, UUID conversationId, String kind, @Nullable UUID authorPrincipalId,
            String body, boolean answersOpenQuestions, String externalId, Map<String, Object> externalAuthor) {
        UUID id = Ids.newId();
        Instant now = time.now();
        String text = body.isBlank() ? "(empty)" : ConversationStore.cut(body, ConversationMessagesImpl.MAX_BODY);
        int inserted = jdbc.sql("""
                INSERT INTO message (id, company_id, conversation_id, kind, author_principal_id, body,
                                     answers_open_questions, unanswered, external_id, external_author, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, false, ?, CAST(? AS jsonb), ?)
                ON CONFLICT ON CONSTRAINT message_external_uq DO NOTHING""")
            .params(id, companyId, conversationId, kind, authorPrincipalId, text, answersOpenQuestions,
                    ConversationStore.cut(externalId, 255), ConversationJson.write(externalAuthor),
                    ConversationStore.ts(now))
            .update();
        if (inserted == 0) {
            return Optional.empty();
        }
        conversations.touch(companyId, conversationId, now);
        return Optional.of(id);
    }

    /** The message of the conversation with this external id. */
    Optional<UUID> messageOf(UUID conversationId, String externalId) {
        return jdbc.sql("SELECT id FROM message WHERE conversation_id = ? AND external_id = ?")
            .params(conversationId, externalId)
            .query(UUID.class)
            .optional();
    }

    /** The conversation's place reference, as text values. */
    Optional<Map<String, String>> placeRef(UUID conversationId) {
        return jdbc.sql("SELECT place_ref::text FROM conversation WHERE id = ? AND place_ref IS NOT NULL")
            .param(conversationId)
            .query(String.class)
            .optional()
            .map(PlaceStore::strings);
    }

    /** The conversation of a chat thread, open or ended (C8). */
    Optional<UUID> ofThread(UUID companyId, UUID systemId, String channelId, String threadId) {
        return jdbc.sql("""
                SELECT id FROM conversation
                 WHERE company_id = ? AND place = 'CHAT_THREAD' AND place_ref ->> 'systemId' = ?
                   AND place_ref ->> 'channelId' = ? AND place_ref ->> 'threadId' = ?""")
            .params(companyId, systemId.toString(), channelId, threadId)
            .query(UUID.class)
            .optional();
    }

    /** The person's open conversations in a chat thread of the system, oldest first. */
    List<UUID> openInChat(UUID companyId, UUID personPrincipalId, UUID systemId) {
        return jdbc.sql("""
                SELECT id FROM conversation
                 WHERE company_id = ? AND person_principal_id = ? AND place = 'CHAT_THREAD' AND state = 'OPEN'
                   AND place_ref ->> 'systemId' = ?
                 ORDER BY created_at, id""")
            .params(companyId, personPrincipalId, systemId.toString())
            .query(UUID.class)
            .list();
    }

    /** Every open conversation in a chat thread of the system, in the lock order (by id). */
    List<UUID> openOnSystem(UUID companyId, UUID systemId) {
        return jdbc.sql("""
                SELECT id FROM conversation
                 WHERE company_id = ? AND place = 'CHAT_THREAD' AND state = 'OPEN' AND place_ref ->> 'systemId' = ?
                 ORDER BY id""")
            .params(companyId, systemId.toString())
            .query(UUID.class)
            .list();
    }

    /** The turn's {@code AGENT} message, its summary (not a decision's card). */
    Optional<String> agentMessage(UUID runId) {
        return jdbc.sql("""
                SELECT body FROM message WHERE run_id = ? AND kind = 'AGENT' AND decision_id IS NULL
                 ORDER BY created_at DESC, id DESC LIMIT 1""")
            .param(runId)
            .query(String.class)
            .optional();
    }

    /** How a decision was decided from the place (V018): {@code CHAT}, the chat user and their name. */
    void decidedFromPlace(UUID decisionId, String externalId, String name) {
        jdbc.sql("""
                UPDATE decision SET decided_via = 'CHAT', decided_by_external_id = ?, decided_by_name = ?
                 WHERE id = ? AND status = 'DECIDED'""")
            .params(ConversationStore.cut(externalId, 255), ConversationStore.cut(name, 255), decisionId)
            .update();
    }

    /** How a decision was decided: {@code via} ({@code CHAT}; null in the app) and the chat name. */
    record Via(@Nullable String via, @Nullable String name) {
    }

    Via via(UUID decisionId) {
        return jdbc.sql("SELECT decided_via, decided_by_name FROM decision WHERE id = ?")
            .param(decisionId)
            .query((rs, row) -> new Via(rs.getString("decided_via"), rs.getString("decided_by_name")))
            .optional()
            .orElse(new Via(null, null));
    }

    private static Map<String, String> strings(String json) {
        Map<String, String> values = new LinkedHashMap<>();
        JsonNode node = ConversationJson.JSON.readTree(json);
        node.properties().forEach(entry -> {
            if (entry.getValue().isValueNode() && !entry.getValue().isNull()) {
                values.put(entry.getKey(), entry.getValue().asString());
            }
        });
        return values;
    }
}
