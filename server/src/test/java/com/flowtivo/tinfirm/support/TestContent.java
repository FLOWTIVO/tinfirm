package com.flowtivo.tinfirm.support;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A conversation with content of every kind, written directly as the runtime role in the company's tenant context (TEST
 * FIXTURE ONLY), for the tests of retention and erasure (the package 6 contract, §3.7): its title and subject's title,
 * a person's message and the agent's, a question answered and an approval of a tool call with its arguments, a link
 * output, a file of the person's, and an ended run with its summary, finish result and two events.
 */
public final class TestContent {

    /** Text that every purged field held, so that a test finds what remains. */
    public static final String SECRET = "secret-words";

    private TestContent() {
    }

    /** The rows of a conversation written by {@link #conversation}. */
    public record Content(UUID conversationId, String key, UUID personMessageId, UUID agentMessageId,
            UUID questionId, UUID approvalId, UUID outputId, UUID attachmentId, UUID runId) {
    }

    /**
     * A conversation of the person with the agent, with content of every kind.
     *
     * @param place  {@code TINFIRM_THREAD} or {@code CHAT_THREAD}
     * @param ended  whether it ended (by its person, now)
     * @param userId the person's user, who added the file
     */
    /**
     * A place's reference: a chat thread's is a member's direct message (package 5a: its system, {@code DM}, its
     * channel and its thread, one conversation per thread), any other place's a channel.
     */
    private static @org.jspecify.annotations.Nullable String placeRef(String place, UUID conversationId) {
        if ("TINFIRM_THREAD".equals(place)) {
            return null;
        }
        if ("CHAT_THREAD".equals(place)) {
            return "{\"systemId\":\"" + UUID.randomUUID() + "\",\"kind\":\"DM\",\"channelId\":\"D1\",\"threadId\":\""
                    + conversationId + "\"}";
        }
        return "{\"channel\":\"C1\"}";
    }

    public static Content conversation(UUID companyId, UUID agentId, UUID personPrincipalId, UUID userId, String key,
            String place, boolean ended) {
        UUID conversationId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        Content content = new Content(conversationId, key, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), runId);
        Instant now = Instant.now();
        try (TenantSql sql = TenantSql.in(companyId)) {
            sql.accepted("""
                    INSERT INTO conversation (id, company_id, agent_id, key, title, place, place_ref, origin,
                                              person_principal_id, subject_key, subject_title, runtime,
                                              payer_principal_id, model_access, turn_count, result_outcome,
                                              result_summary, result_next_steps, result_open_questions, created_at,
                                              updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, 'SHOP-1', ?, 'CLAUDE_CODE', ?, 'BUDGET', 1,
                            'COMPLETED', ?, '["a step with secret-words"]'::jsonb, '[]'::jsonb, ?, ?)""",
                    conversationId, companyId, agentId, key, "Title with " + SECRET, place, placeRef(place,
                            conversationId), "CHAT_THREAD".equals(place) ? "DIRECT_MESSAGE" : "APP", personPrincipalId,
                    "Subject with " + SECRET, personPrincipalId, "Result with " + SECRET, ts(now), ts(now));
            sql.accepted("""
                    INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                                     turn_number, acting_principal_id, payer_principal_id, model_access, status, epoch,
                                     branch, created_at, started_at, finished_at, summary, failure_message,
                                     result_outcome, result_next_steps, result_open_questions)
                    VALUES (?, ?, ?, ?, ?, 'CLAUDE_CODE', 'FIRST', 1, ?, ?, 'BUDGET', 'SUCCEEDED', 1, ?, ?, ?, ?, ?, NULL,
                            'COMPLETED', '["next secret-words"]'::jsonb, '[]'::jsonb)""",
                    runId, companyId, conversationId, key, agentId, personPrincipalId, personPrincipalId,
                    "agent/" + key, ts(now), ts(now), ts(now), "Summary with " + SECRET);
            for (int seq = 1; seq <= 2; seq++) {
                sql.accepted("""
                        INSERT INTO run_event (run_id, seq, company_id, type, at, payload)
                        VALUES (?, ?, ?, 'AGENT_MESSAGE', ?, CAST(? AS jsonb))""",
                        runId, (long) seq, companyId, ts(now), "{\"text\":\"" + SECRET + "\"}");
            }
            sql.accepted("""
                    INSERT INTO message (id, company_id, conversation_id, kind, author_principal_id, body, run_id,
                                         created_at)
                    VALUES (?, ?, ?, 'PERSON', ?, ?, ?, ?)""",
                    content.personMessageId(), companyId, conversationId, personPrincipalId, "Asks " + SECRET, runId,
                    ts(now));
            sql.accepted("""
                    INSERT INTO message (id, company_id, conversation_id, kind, body, run_id, created_at)
                    VALUES (?, ?, ?, 'AGENT', ?, ?, ?)""",
                    content.agentMessageId(), companyId, conversationId, "Answers " + SECRET, runId,
                    ts(now.plusMillis(1)));
            sql.accepted("""
                    INSERT INTO decision (id, company_id, conversation_id, run_id, kind, question, options,
                                          approver_principal_id, status, outcome, answer, decided_by_principal_id,
                                          decided_at, created_at)
                    VALUES (?, ?, ?, ?, 'QUESTION', ?, '["yes", "no"]'::jsonb, ?, 'DECIDED', 'ANSWERED', ?, ?, ?, ?)""",
                    content.questionId(), companyId, conversationId, runId, "Question on " + SECRET,
                    personPrincipalId, "Answer with " + SECRET, personPrincipalId, ts(now), ts(now));
            sql.accepted("""
                    INSERT INTO decision (id, company_id, conversation_id, run_id, kind, subject, reason,
                                          approver_principal_id, status, outcome, note, decided_by_principal_id,
                                          decided_at, created_at)
                    VALUES (?, ?, ?, ?, 'APPROVAL', CAST(? AS jsonb), ?, ?, 'DECIDED', 'APPROVED', ?, ?, ?, ?)""",
                    content.approvalId(), companyId, conversationId, runId,
                    "{\"type\":\"TOOL_CALL\",\"systemId\":\"" + UUID.randomUUID() + "\",\"name\":\"jira_comment\","
                            + "\"tool\":\"comment\",\"arguments\":{\"body\":\"" + SECRET + "\"},\"what\":\"comment "
                            + SECRET + "\"}",
                    "Because " + SECRET, personPrincipalId, "Note " + SECRET, personPrincipalId, ts(now), ts(now));
            byte[] file = ("File with " + SECRET).getBytes(StandardCharsets.UTF_8);
            sql.accepted("""
                    INSERT INTO attachment (id, company_id, conversation_id, file_name, content_type, size_bytes, sha256,
                                            content, note, uploaded_by_user_id, created_at)
                    VALUES (?, ?, ?, 'notes.txt', 'text/plain', ?, ?, ?, ?, ?, ?)""",
                    content.attachmentId(), companyId, conversationId, (long) file.length, sha256(file), file,
                    "Note " + SECRET, userId, ts(now));
            sql.accepted("""
                    INSERT INTO output (id, company_id, conversation_id, run_id, kind, title, url, created_at)
                    VALUES (?, ?, ?, ?, 'LINK', ?, 'https://example.test/report', ?)""",
                    content.outputId(), companyId, conversationId, runId, "Link " + SECRET, ts(now));
            if (ended) {
                sql.accepted("""
                        UPDATE conversation SET state = 'ENDED', ended_reason = 'USER', ended_at = ? WHERE id = ?""",
                        ts(now), conversationId);
            }
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return content;
    }

    /**
     * An ended conversation of a schedule (package 4) that the author authored and pays, with no person: the agent's
     * message and one of the speaker's, a member of the trigger's people, each holding {@link #SECRET}.
     *
     * @return the conversation's id
     */
    public static UUID triggerConversation(UUID companyId, UUID agentId, UUID authorPrincipalId,
            UUID speakerPrincipalId, String key) {
        UUID triggerId = TestTriggers.schedule(companyId, agentId, authorPrincipalId, authorPrincipalId);
        UUID conversationId = UUID.randomUUID();
        TestTriggers.conversation(companyId, conversationId, key, triggerId, agentId, authorPrincipalId,
                authorPrincipalId);
        Instant now = Instant.now();
        try (TenantSql sql = TenantSql.in(companyId)) {
            sql.accepted("""
                    INSERT INTO message (id, company_id, conversation_id, kind, body, created_at)
                    VALUES (?, ?, ?, 'AGENT', ?, ?)""",
                    UUID.randomUUID(), companyId, conversationId, "Reports " + SECRET, ts(now));
            sql.accepted("""
                    INSERT INTO message (id, company_id, conversation_id, kind, author_principal_id, body, created_at)
                    VALUES (?, ?, ?, 'PERSON', ?, ?, ?)""",
                    UUID.randomUUID(), companyId, conversationId, speakerPrincipalId, "Says " + SECRET,
                    ts(now.plusMillis(1)));
            sql.accepted("""
                    UPDATE conversation SET state = 'ENDED', ended_reason = 'USER', ended_at = ? WHERE id = ?""",
                    ts(now), conversationId);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return conversationId;
    }

    /** The conversation's rows that still hold {@link #SECRET}, by table: what a purge left. */
    public static long secretsIn(UUID companyId, UUID conversationId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("""
                    SELECT (SELECT count(*) FROM conversation WHERE id = ? AND (title LIKE '%secret-words%'
                                OR subject_title LIKE '%secret-words%' OR result_summary LIKE '%secret-words%'
                                OR result_next_steps::text LIKE '%secret-words%'))
                         + (SELECT count(*) FROM message WHERE conversation_id = ? AND body LIKE '%secret-words%')
                         + (SELECT count(*) FROM decision WHERE conversation_id = ?
                                AND concat(question, options::text, reason, answer, note, result, subject::text)
                                    LIKE '%secret-words%')
                         + (SELECT count(*) FROM output WHERE conversation_id = ? AND title LIKE '%secret-words%')
                         + (SELECT count(*) FROM attachment WHERE conversation_id = ?
                                AND (content IS NOT NULL OR note IS NOT NULL))
                         + (SELECT count(*) FROM run WHERE conversation_id = ?
                                AND concat(summary, failure_message, result_next_steps::text) LIKE '%secret-words%')
                         + (SELECT count(*) FROM run_event e JOIN run r ON r.id = e.run_id
                             WHERE r.conversation_id = ?)""",
                    conversationId, conversationId, conversationId, conversationId, conversationId, conversationId,
                    conversationId);
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** One column of one row, as the runtime role in the company's context. */
    public static @Nullable Object column(UUID companyId, String sql, Object... params) {
        try (TenantSql tenant = TenantSql.in(companyId)) {
            var values = tenant.column(sql, params);
            return values.isEmpty() ? null : values.getFirst();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static java.time.OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
