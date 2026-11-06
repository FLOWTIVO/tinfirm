package com.flowtivo.tinfirm.mcp.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The Gmail add-on's rows (ADR-0083; {@code V056__gmail_add_on.sql}): the Google accounts' links, the sign-in links
 * offered to them, and the conversations people started about emails. Every statement runs in the caller's
 * transaction and tenant context: row-level security keeps it in the company.
 */
@Repository
class GmailStore {

    /** The most emails whose conversation a person's add-on remembers in a company. */
    static final int MAX_REMEMBERED = 100;

    private final JdbcClient jdbc;

    GmailStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- links ---------------------------------------------------------------------------------------------------------

    /** A Google account's link to its person. */
    record Link(UUID id, UUID companyId, UUID userId, String googleSubject, @Nullable String googleEmail,
            @Nullable UUID lastAgentId, Instant linkedAt, Instant lastUsedAt) {
    }

    private static final String LINK_COLUMNS = "id, company_id, user_id, google_subject, google_email, last_agent_id,"
            + " linked_at, last_used_at";

    Optional<Link> linkOf(UUID companyId, String googleSubject) {
        return jdbc.sql("SELECT " + LINK_COLUMNS + " FROM gmail_link WHERE company_id = ? AND google_subject = ?")
            .params(companyId, googleSubject)
            .query(GmailStore::link)
            .optional();
    }

    Optional<Link> link(UUID linkId) {
        return jdbc.sql("SELECT " + LINK_COLUMNS + " FROM gmail_link WHERE id = ?")
            .param(linkId)
            .query(GmailStore::link)
            .optional();
    }

    /** The user's links in the tenant's company, newest first. */
    List<Link> linksOf(UUID userId) {
        return jdbc.sql("SELECT " + LINK_COLUMNS + " FROM gmail_link WHERE user_id = ? ORDER BY linked_at DESC")
            .param(userId)
            .query(GmailStore::link)
            .list();
    }

    /**
     * Links the account to the user, or renews its link to them; empty, and nothing changed, when the account is linked
     * to another user.
     */
    Optional<Link> upsertLink(UUID id, UUID companyId, UUID userId, String googleSubject, @Nullable String googleEmail,
            Instant at) {
        return jdbc.sql("""
                INSERT INTO gmail_link (id, company_id, user_id, google_subject, google_email, linked_at, last_used_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (company_id, google_subject) DO UPDATE
                   SET google_email = excluded.google_email, linked_at = excluded.linked_at,
                       last_used_at = excluded.last_used_at
                 WHERE gmail_link.user_id = excluded.user_id
                RETURNING""" + " " + LINK_COLUMNS)
            .params(id, companyId, userId, googleSubject, googleEmail, ts(at), ts(at))
            .query(GmailStore::link)
            .optional();
    }

    void touch(UUID linkId, Instant at) {
        jdbc.sql("UPDATE gmail_link SET last_used_at = ? WHERE id = ? AND last_used_at < ?")
            .params(ts(at), linkId, ts(at))
            .update();
    }

    void lastAgent(UUID linkId, UUID agentId) {
        jdbc.sql("UPDATE gmail_link SET last_agent_id = ? WHERE id = ?").params(agentId, linkId).update();
    }

    boolean deleteLink(UUID linkId) {
        return jdbc.sql("DELETE FROM gmail_link WHERE id = ?").param(linkId).update() > 0;
    }

    private static Link link(ResultSet rs, int row) throws SQLException {
        return new Link(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("user_id", UUID.class), rs.getString("google_subject"), rs.getString("google_email"),
                rs.getObject("last_agent_id", UUID.class), instant(rs, "linked_at"), instant(rs, "last_used_at"));
    }

    // ---- sign-in links -------------------------------------------------------------------------------------------------

    /** A sign-in link offered to a Google account. */
    record LinkRequest(String tokenHash, UUID companyId, String googleSubject, @Nullable String googleEmail,
            Instant createdAt, Instant expiresAt, @Nullable Instant consumedAt) {
    }

    /**
     * Offers a new sign-in link to the account: its unused ones go, and so do the company's requests that expired a day
     * ago, so that an account has one link at a time and the table stays small. The account's offers take their turn
     * under a transaction-scoped advisory lock ({@code gmail_link_request:{companyId}:{subject}}, CONVENTIONS §10),
     * before any row, so that two at once (Gmail's homepage and an email's trigger) never both leave a link.
     */
    void offer(LinkRequest request) {
        jdbc.sql("SELECT pg_advisory_xact_lock(hashtextextended(?, 0))")
            .params("gmail_link_request:" + request.companyId() + ":" + request.googleSubject())
            .query((rs, row) -> 1)
            .list();
        jdbc.sql("DELETE FROM gmail_link_request WHERE company_id = ? AND google_subject = ? AND consumed_at IS NULL")
            .params(request.companyId(), request.googleSubject())
            .update();
        jdbc.sql("DELETE FROM gmail_link_request WHERE company_id = ? AND expires_at < ?")
            .params(request.companyId(), ts(request.createdAt().minusSeconds(86_400)))
            .update();
        jdbc.sql("""
                INSERT INTO gmail_link_request (token_hash, company_id, google_subject, google_email, created_at,
                                                expires_at)
                VALUES (?, ?, ?, ?, ?, ?)""")
            .params(request.tokenHash(), request.companyId(), request.googleSubject(), request.googleEmail(),
                    ts(request.createdAt()), ts(request.expiresAt()))
            .update();
    }

    Optional<LinkRequest> request(String tokenHash, boolean forUpdate) {
        return jdbc.sql("SELECT token_hash, company_id, google_subject, google_email, created_at, expires_at,"
                + " consumed_at FROM gmail_link_request WHERE token_hash = ?" + (forUpdate ? " FOR UPDATE" : ""))
            .param(tokenHash)
            .query((rs, row) -> new LinkRequest(rs.getString("token_hash"), rs.getObject("company_id", UUID.class),
                    rs.getString("google_subject"), rs.getString("google_email"), instant(rs, "created_at"),
                    instant(rs, "expires_at"), nullableInstant(rs, "consumed_at")))
            .optional();
    }

    void consume(String tokenHash, UUID userId, Instant at) {
        jdbc.sql("UPDATE gmail_link_request SET consumed_at = ?, consumed_by_user_id = ? WHERE token_hash = ?"
                + " AND consumed_at IS NULL")
            .params(ts(at), userId, tokenHash)
            .update();
    }

    // ---- emails' conversations -----------------------------------------------------------------------------------------

    /** The conversation a person started about an email. */
    record EmailConversation(String emailKey, @Nullable String threadKey, UUID conversationId, String action,
            Instant startedAt) {
    }

    /** The email's conversation, else the newest of its thread's. */
    Optional<EmailConversation> conversationOf(UUID companyId, UUID userId, String emailKey,
            @Nullable String threadKey) {
        Optional<EmailConversation> own = jdbc.sql("SELECT email_key, thread_key, conversation_id, action, started_at"
                + " FROM gmail_email_conversation WHERE company_id = ? AND user_id = ? AND email_key = ?")
            .params(companyId, userId, emailKey)
            .query(GmailStore::emailConversation)
            .optional();
        if (own.isPresent() || threadKey == null) {
            return own;
        }
        return jdbc.sql("SELECT email_key, thread_key, conversation_id, action, started_at"
                + " FROM gmail_email_conversation WHERE company_id = ? AND user_id = ? AND thread_key = ?"
                + " ORDER BY started_at DESC LIMIT 1")
            .params(companyId, userId, threadKey)
            .query(GmailStore::emailConversation)
            .optional();
    }

    /** Remembers the email's conversation, and forgets the person's oldest beyond {@link #MAX_REMEMBERED}. */
    void remember(UUID companyId, UUID userId, EmailConversation conversation) {
        jdbc.sql("""
                INSERT INTO gmail_email_conversation (company_id, user_id, email_key, thread_key, conversation_id,
                                                      action, started_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (company_id, user_id, email_key) DO UPDATE
                   SET thread_key = excluded.thread_key, conversation_id = excluded.conversation_id,
                       action = excluded.action, started_at = excluded.started_at""")
            .params(companyId, userId, conversation.emailKey(), conversation.threadKey(),
                    conversation.conversationId(), conversation.action(), ts(conversation.startedAt()))
            .update();
        jdbc.sql("""
                DELETE FROM gmail_email_conversation
                 WHERE company_id = ? AND user_id = ? AND email_key IN (
                       SELECT email_key FROM gmail_email_conversation WHERE company_id = ? AND user_id = ?
                        ORDER BY started_at DESC OFFSET ?)""")
            .params(companyId, userId, companyId, userId, MAX_REMEMBERED)
            .update();
    }

    /** Forgets the conversation of the email, and of every email of its thread. */
    void forget(UUID companyId, UUID userId, String emailKey, @Nullable String threadKey) {
        jdbc.sql("DELETE FROM gmail_email_conversation WHERE company_id = ? AND user_id = ?"
                + " AND (email_key = ? OR thread_key = ?)")
            .params(companyId, userId, emailKey, threadKey)
            .update();
    }

    private static EmailConversation emailConversation(ResultSet rs, int row) throws SQLException {
        return new EmailConversation(rs.getString("email_key"), rs.getString("thread_key"),
                rs.getObject("conversation_id", UUID.class), rs.getString("action"), instant(rs, "started_at"));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------------

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }
}
