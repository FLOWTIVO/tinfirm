package com.flowtivo.tinfirm.chat.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL of the chat module's tables (V017), under row-level security: what arrived ({@code chat_event}), chat users,
 * channels, sign-in links, default agents, the company's rates, the inbox's items, what the bot posted, and the Socket
 * Mode slots. Cross-company candidates come from the {@code SECURITY DEFINER} lookups, ids only; each row is then read
 * again in its company's tenant context.
 */
@Repository
class ChatStore {

    static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    ChatStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // ---- systems by id (C2) ---------------------------------------------------------------------------------------

    /** An inbound request's system: its company and kind, from the endpoint's id alone. */
    record SystemRef(UUID systemId, UUID companyId, String kind) {
    }

    /** In a transaction without a company: the definer lookup. */
    Optional<SystemRef> system(UUID systemId) {
        return jdbc.sql("SELECT system_id, company_id, kind FROM chat_system_by_id(?)")
            .param(systemId)
            .query((rs, row) -> new SystemRef(rs.getObject("system_id", UUID.class),
                    rs.getObject("company_id", UUID.class), rs.getString("kind")))
            .optional();
    }

    /** Every chat system with a bot, of every company. */
    List<SystemRef> systemsWithBot() {
        return jdbc.sql("SELECT system_id, company_id, kind FROM chat_systems_with_bot()")
            .query((rs, row) -> new SystemRef(rs.getObject("system_id", UUID.class),
                    rs.getObject("company_id", UUID.class), rs.getString("kind")))
            .list();
    }

    // ---- chat_event (§3.7) ----------------------------------------------------------------------------------------

    /** An event as it arrives, verified and normalized. */
    record NewEvent(UUID id, UUID companyId, UUID systemId, String eventId, String kind, @Nullable String sender,
            String threadKey, BigDecimal platformTs, int retryNum, Map<String, Object> payload, String status,
            @Nullable String reason, Instant receivedAt) {
    }

    /** Stores the event once (C3): false when the system has an event of this id already. */
    boolean insert(NewEvent event) {
        boolean handled = !"RECEIVED".equals(event.status());
        return jdbc.sql("""
                INSERT INTO chat_event (id, company_id, system_id, event_id, kind, sender, thread_key, platform_ts,
                                        retry_num, payload, status, reason, next_attempt_at, received_at, handled_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?, ?)
                ON CONFLICT (system_id, event_id) DO NOTHING""")
            .params(event.id(), event.companyId(), event.systemId(), cut(event.eventId(), 512), event.kind(),
                    event.sender(), cut(event.threadKey(), 600), event.platformTs(), event.retryNum(),
                    handled ? null : JSON.writeValueAsString(event.payload()), event.status(), event.reason(),
                    ts(event.receivedAt()), ts(event.receivedAt()), handled ? ts(event.receivedAt()) : null)
            .update() == 1;
    }

    /** A stored event. */
    record EventRow(UUID id, UUID companyId, UUID systemId, String eventId, String kind, @Nullable String sender,
            String threadKey, Map<String, Object> payload, String status, @Nullable String reason, int attempts,
            Instant receivedAt) {
    }

    private static final String EVENT_COLUMNS = "id, company_id, system_id, event_id, kind, sender, thread_key, "
            + "payload, status, reason, attempts, received_at";

    /** The oldest due event of each thread, of every company (the definer lookup, in a transaction without one). */
    List<UUID[]> due(Instant now, int limit) {
        return jdbc.sql("SELECT event_id, company_id FROM chat_event_due(?, ?)")
            .params(ts(now), limit)
            .query((rs, row) -> new UUID[] { rs.getObject("event_id", UUID.class),
                    rs.getObject("company_id", UUID.class) })
            .list();
    }

    /** Held events whose hold ran out, and events older than a day, of every company. */
    List<UUID[]> expired(Instant now, int limit) {
        return jdbc.sql("SELECT event_id, company_id FROM chat_event_expired(?, ?)")
            .params(ts(now), limit)
            .query((rs, row) -> new UUID[] { rs.getObject("event_id", UUID.class),
                    rs.getObject("company_id", UUID.class) })
            .list();
    }

    /** Claims a due event for {@code claimFor}, unless another replica holds it: one attempt more. */
    Optional<EventRow> claim(UUID id, Instant now, Instant until) {
        Optional<EventRow> row = jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM chat_event WHERE id = ?"
                + " AND status = 'RECEIVED' AND next_attempt_at <= ? AND (claimed_until IS NULL OR claimed_until < ?)"
                + " FOR UPDATE SKIP LOCKED")
            .params(id, ts(now), ts(now))
            .query(ChatStore::event)
            .optional();
        row.ifPresent(event -> jdbc.sql("UPDATE chat_event SET claimed_until = ?, attempts = attempts + 1"
                + " WHERE id = ?").params(ts(until), id).update());
        return row.map(event -> new EventRow(event.id(), event.companyId(), event.systemId(), event.eventId(),
                event.kind(), event.sender(), event.threadKey(), event.payload(), event.status(), event.reason(),
                event.attempts() + 1, event.receivedAt()));
    }

    Optional<EventRow> find(UUID id) {
        return jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM chat_event WHERE id = ?")
            .param(id)
            .query(ChatStore::event)
            .optional();
    }

    Optional<EventRow> lock(UUID id) {
        return jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM chat_event WHERE id = ? FOR UPDATE")
            .param(id)
            .query(ChatStore::event)
            .optional();
    }

    /** The event's end: its status and reason; its payload cleared. */
    void finish(UUID id, String status, @Nullable String reason, Instant at) {
        jdbc.sql("""
                UPDATE chat_event SET status = ?, reason = ?, payload = NULL, claimed_until = NULL, held_until = NULL,
                                      handled_at = ?
                 WHERE id = ?""")
            .params(status, reason, ts(at), id)
            .update();
    }

    /** Held: for a sign-in link or the agent picker, until the hold runs out. */
    void hold(UUID id, String status, Instant until) {
        jdbc.sql("UPDATE chat_event SET status = ?, held_until = ?, claimed_until = NULL WHERE id = ?")
            .params(status, ts(until), id)
            .update();
    }

    /** A held event goes back to be handled now. */
    void release(UUID id, Instant now) {
        jdbc.sql("""
                UPDATE chat_event SET status = 'RECEIVED', held_until = NULL, claimed_until = NULL,
                                      next_attempt_at = ?, attempts = 0
                 WHERE id = ? AND status IN ('AWAITING_LINK', 'AWAITING_AGENT')""")
            .params(ts(now), id)
            .update();
    }

    /** A failed attempt: tried again at {@code next}. */
    void retry(UUID id, Instant next) {
        jdbc.sql("UPDATE chat_event SET claimed_until = NULL, next_attempt_at = ? WHERE id = ?")
            .params(ts(next), id)
            .update();
    }

    /** The events a chat user has held, oldest first. */
    List<EventRow> held(UUID systemId, String sender, String status) {
        return jdbc.sql("SELECT " + EVENT_COLUMNS + " FROM chat_event WHERE system_id = ? AND sender = ?"
                + " AND status = ? ORDER BY received_at, id")
            .params(systemId, sender, status)
            .query(ChatStore::event)
            .list();
    }

    void delete(UUID id) {
        jdbc.sql("DELETE FROM chat_event WHERE id = ?").param(id).update();
    }

    /** Events of a system received since, for its silence (§5.2). */
    boolean receivedSince(UUID systemId, Instant since) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM chat_event WHERE system_id = ? AND received_at >= ?)")
            .params(systemId, ts(since))
            .query(Boolean.class)
            .single();
    }

    private static EventRow event(ResultSet rs, int row) throws SQLException {
        String payload = rs.getString("payload");
        return new EventRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getString("event_id"), rs.getString("kind"),
                rs.getString("sender"), rs.getString("thread_key"), payload != null ? map(payload) : Map.of(),
                rs.getString("status"), rs.getString("reason"), rs.getInt("attempts"),
                rs.getObject("received_at", OffsetDateTime.class).toInstant());
    }

    // ---- chat_user (§3.3) -----------------------------------------------------------------------------------------

    /** A chat user as Tinfirm knows them. */
    record ChatUser(UUID companyId, UUID systemId, String externalId, @Nullable String aadObjectId,
            @Nullable String teamId, @Nullable String audience, @Nullable String displayName,
            Map<String, Object> dmRef, @Nullable Instant checkedAt, Instant lastSeenAt, @Nullable Instant noticeShownAt,
            @Nullable Instant linkOfferedAt, @Nullable Instant refusalToldAt) {
    }

    private static final String USER_COLUMNS = "company_id, system_id, external_id, aad_object_id, team_id, audience,"
            + " display_name, dm_ref, checked_at, last_seen_at, notice_shown_at, link_offered_at, refusal_told_at";

    /** The sender is seen now: a row for every sender (§3.3), with what the event said of them. */
    void seen(UUID companyId, UUID systemId, String externalId, @Nullable String aadObjectId,
            @Nullable String displayName, @Nullable Map<String, Object> dmRef, Instant at) {
        jdbc.sql("""
                INSERT INTO chat_user (company_id, system_id, external_id, aad_object_id, display_name, dm_ref,
                                       last_seen_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?)
                ON CONFLICT (system_id, external_id) DO UPDATE
                   SET last_seen_at = excluded.last_seen_at,
                       aad_object_id = coalesce(excluded.aad_object_id, chat_user.aad_object_id),
                       display_name = coalesce(excluded.display_name, chat_user.display_name),
                       dm_ref = coalesce(excluded.dm_ref, chat_user.dm_ref)""")
            .params(companyId, systemId, cut(externalId, 255), aadObjectId, cut(displayName, 200),
                    dmRef != null ? JSON.writeValueAsString(dmRef) : null, ts(at))
            .update();
    }

    Optional<ChatUser> user(UUID systemId, String externalId) {
        return jdbc.sql("SELECT " + USER_COLUMNS + " FROM chat_user WHERE system_id = ? AND external_id = ?")
            .params(systemId, externalId)
            .query(ChatStore::user)
            .optional();
    }

    Optional<ChatUser> lockUser(UUID systemId, String externalId) {
        return jdbc.sql("SELECT " + USER_COLUMNS + " FROM chat_user WHERE system_id = ? AND external_id = ?"
                + " FOR UPDATE")
            .params(systemId, externalId)
            .query(ChatStore::user)
            .optional();
    }

    /** The chat user of a Teams Entra object id. */
    Optional<ChatUser> userByAad(UUID systemId, String aadObjectId) {
        return jdbc.sql("SELECT " + USER_COLUMNS + " FROM chat_user WHERE system_id = ? AND aad_object_id = ?"
                + " ORDER BY last_seen_at DESC LIMIT 1")
            .params(systemId, aadObjectId)
            .query(ChatStore::user)
            .optional();
    }

    /** What the platform said of the user just now (§5.3). */
    void checked(UUID systemId, String externalId, String audience, @Nullable String teamId,
            @Nullable String displayName, @Nullable String aadObjectId, Instant at) {
        jdbc.sql("""
                UPDATE chat_user SET audience = ?, team_id = ?, display_name = coalesce(?, display_name),
                                     aad_object_id = coalesce(?, aad_object_id), checked_at = ?
                 WHERE system_id = ? AND external_id = ?""")
            .params(audience, cut(teamId, 255), cut(displayName, 200), aadObjectId, ts(at), systemId, externalId)
            .update();
    }

    /** The user changed at the platform: read again before the next use, nothing kept of before. */
    void stale(UUID systemId, String externalId) {
        jdbc.sql("UPDATE chat_user SET checked_at = NULL, audience = NULL WHERE system_id = ? AND external_id = ?")
            .params(systemId, externalId)
            .update();
    }

    void dmRef(UUID systemId, String externalId, Map<String, Object> dmRef) {
        jdbc.sql("UPDATE chat_user SET dm_ref = CAST(? AS jsonb) WHERE system_id = ? AND external_id = ?")
            .params(JSON.writeValueAsString(dmRef), systemId, externalId)
            .update();
    }

    void noticeShown(UUID systemId, String externalId, Instant at) {
        jdbc.sql("UPDATE chat_user SET notice_shown_at = ? WHERE system_id = ? AND external_id = ?")
            .params(ts(at), systemId, externalId)
            .update();
    }

    void linkOffered(UUID systemId, String externalId, Instant at) {
        jdbc.sql("UPDATE chat_user SET link_offered_at = ? WHERE system_id = ? AND external_id = ?")
            .params(ts(at), systemId, externalId)
            .update();
    }

    /** The refusal is told now, unless it was told within the interval: whether to tell it. */
    boolean tellRefusal(UUID systemId, String externalId, Instant at, Instant toldBefore) {
        return jdbc.sql("""
                UPDATE chat_user SET refusal_told_at = ?
                 WHERE system_id = ? AND external_id = ? AND (refusal_told_at IS NULL OR refusal_told_at < ?)""")
            .params(ts(at), systemId, externalId, ts(toldBefore))
            .update() == 1;
    }

    /** The company's stop is told now, unless it was told within the interval (V028): whether to tell it. */
    boolean tellStop(UUID systemId, String externalId, Instant at, Instant toldBefore) {
        return jdbc.sql("""
                UPDATE chat_user SET stop_told_at = ?
                 WHERE system_id = ? AND external_id = ? AND (stop_told_at IS NULL OR stop_told_at < ?)""")
            .params(ts(at), systemId, externalId, ts(toldBefore))
            .update() == 1;
    }

    private static ChatUser user(ResultSet rs, int row) throws SQLException {
        String dmRef = rs.getString("dm_ref");
        return new ChatUser(rs.getObject("company_id", UUID.class), rs.getObject("system_id", UUID.class),
                rs.getString("external_id"), rs.getString("aad_object_id"), rs.getString("team_id"),
                rs.getString("audience"), rs.getString("display_name"), dmRef != null ? map(dmRef) : Map.of(),
                instant(rs, "checked_at"), rs.getObject("last_seen_at", OffsetDateTime.class).toInstant(),
                instant(rs, "notice_shown_at"), instant(rs, "link_offered_at"), instant(rs, "refusal_told_at"));
    }

    // ---- chat_channel (§3.4) --------------------------------------------------------------------------------------

    void channel(UUID companyId, UUID systemId, String channelId, @Nullable String teamId, @Nullable String name,
            @Nullable String kind, @Nullable Boolean botMember, @Nullable Boolean archived, @Nullable Boolean deleted,
            Instant at) {
        jdbc.sql("""
                INSERT INTO chat_channel (company_id, system_id, channel_id, team_id, name, kind, bot_member, archived,
                                          deleted, updated_at)
                VALUES (?, ?, ?, ?, ?, coalesce(?, 'PUBLIC'), coalesce(?, true), coalesce(?, false),
                        coalesce(?, false), ?)
                ON CONFLICT (system_id, channel_id) DO UPDATE
                   SET team_id = coalesce(excluded.team_id, chat_channel.team_id),
                       name = coalesce(?, chat_channel.name),
                       kind = coalesce(?, chat_channel.kind),
                       bot_member = coalesce(?, chat_channel.bot_member),
                       archived = coalesce(?, chat_channel.archived),
                       deleted = coalesce(?, chat_channel.deleted),
                       updated_at = excluded.updated_at""")
            .params(companyId, systemId, cut(channelId, 255), teamId, cut(name, 255), kind, botMember, archived,
                    deleted, ts(at), cut(name, 255), kind, botMember, archived, deleted)
            .update();
    }

    /** A channel as kept: {@code kind}, {@code bot_member}, {@code archived}, {@code deleted}. */
    record Channel(String channelId, @Nullable String name, String kind, boolean botMember, boolean archived,
            boolean deleted, @Nullable String teamId) {

        /** A channel trigger may be saved on it, and its rows are {@code ACTIVE} (§3.10). */
        boolean available() {
            return botMember && !archived && !deleted && !"SHARED".equals(kind);
        }
    }

    Optional<Channel> channel(UUID systemId, String channelId) {
        return jdbc.sql("SELECT channel_id, name, kind, bot_member, archived, deleted, team_id FROM chat_channel"
                + " WHERE system_id = ? AND channel_id = ?")
            .params(systemId, channelId)
            .query(ChatStore::channelRow)
            .optional();
    }

    /** The system's channels, of these ids; every one the bot knows, not deleted, without ids (package 5b). */
    List<Channel> channels(UUID systemId, java.util.@Nullable Collection<String> channelIds) {
        if (channelIds != null && channelIds.isEmpty()) {
            return List.of();
        }
        org.springframework.jdbc.core.simple.JdbcClient.StatementSpec spec = jdbc.sql("SELECT channel_id, name, kind,"
                + " bot_member, archived, deleted, team_id FROM chat_channel WHERE system_id = :systemId"
                + (channelIds != null ? " AND channel_id IN (:ids)" : " AND NOT deleted")
                + " ORDER BY lower(coalesce(name, channel_id)), channel_id")
            .param("systemId", systemId);
        if (channelIds != null) {
            spec = spec.param("ids", java.util.Set.copyOf(channelIds));
        }
        return spec.query(ChatStore::channelRow).list();
    }

    private static Channel channelRow(ResultSet rs, int row) throws SQLException {
        return new Channel(rs.getString("channel_id"), rs.getString("name"), rs.getString("kind"),
                rs.getBoolean("bot_member"), rs.getBoolean("archived"), rs.getBoolean("deleted"),
                rs.getString("team_id"));
    }

    /** A Teams channel's {@code serviceUrl}, from its last activity (package 5b). */
    void channelServiceUrl(UUID systemId, String channelId, String serviceUrl) {
        jdbc.sql("UPDATE chat_channel SET service_url = ? WHERE system_id = ? AND channel_id = ?"
                + " AND service_url IS DISTINCT FROM ?")
            .params(cut(serviceUrl, 2000), systemId, channelId, cut(serviceUrl, 2000))
            .update();
    }

    Optional<String> channelServiceUrl(UUID systemId, String channelId) {
        return jdbc.sql("SELECT service_url FROM chat_channel WHERE system_id = ? AND channel_id = ?")
            .params(systemId, channelId)
            .query(String.class)
            .optional()
            .filter(java.util.Objects::nonNull);
    }

    // ---- chat_link_request (§3.5) ---------------------------------------------------------------------------------

    record LinkRequest(String tokenHash, UUID companyId, UUID systemId, String externalId,
            @Nullable String displayName, @Nullable String teamId, @Nullable String teamName, List<UUID> heldEventIds,
            Instant createdAt, Instant expiresAt, @Nullable Instant consumedAt, @Nullable String outcome) {
    }

    private static final String LINK_COLUMNS = "token_hash, company_id, system_id, external_id, display_name, team_id,"
            + " team_name, held_event_ids, created_at, expires_at, consumed_at, outcome";

    void insertLink(LinkRequest link) {
        jdbc.sql("""
                INSERT INTO chat_link_request (token_hash, company_id, system_id, external_id, display_name, team_id,
                                               team_name, held_event_ids, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS uuid[]), ?, ?)""")
            .params(link.tokenHash(), link.companyId(), link.systemId(), link.externalId(),
                    cut(link.displayName(), 200), link.teamId(), cut(link.teamName(), 255),
                    link.heldEventIds().stream().map(UUID::toString).toArray(String[]::new), ts(link.createdAt()),
                    ts(link.expiresAt()))
            .update();
    }

    Optional<LinkRequest> link(String tokenHash, boolean forUpdate) {
        return jdbc.sql("SELECT " + LINK_COLUMNS + " FROM chat_link_request WHERE token_hash = ?"
                + (forUpdate ? " FOR UPDATE" : ""))
            .param(tokenHash)
            .query(ChatStore::link)
            .optional();
    }

    /** The chat user's pending request: unconsumed and unexpired, the newest. */
    Optional<LinkRequest> pendingLink(UUID systemId, String externalId, Instant now) {
        return jdbc.sql("SELECT " + LINK_COLUMNS + " FROM chat_link_request WHERE system_id = ? AND external_id = ?"
                + " AND consumed_at IS NULL AND expires_at > ? ORDER BY created_at DESC LIMIT 1 FOR UPDATE")
            .params(systemId, externalId, ts(now))
            .query(ChatStore::link)
            .optional();
    }

    void holdOnLink(String tokenHash, UUID eventId) {
        jdbc.sql("""
                UPDATE chat_link_request SET held_event_ids = array_append(held_event_ids, ?)
                 WHERE token_hash = ? AND cardinality(held_event_ids) < 5 AND NOT (? = ANY (held_event_ids))""")
            .params(eventId, tokenHash, eventId)
            .update();
    }

    void consumeLink(String tokenHash, UUID userId, String outcome, @Nullable String refusedReason, Instant at) {
        jdbc.sql("""
                UPDATE chat_link_request SET consumed_at = ?, consumed_by_user_id = ?, outcome = ?, refused_reason = ?
                 WHERE token_hash = ? AND consumed_at IS NULL""")
            .params(ts(at), userId, outcome, refusedReason, tokenHash)
            .update();
    }

    private static LinkRequest link(ResultSet rs, int row) throws SQLException {
        java.sql.Array held = rs.getArray("held_event_ids");
        List<UUID> heldIds = new ArrayList<>();
        if (held != null) {
            for (Object value : (Object[]) held.getArray()) {
                heldIds.add(value instanceof UUID uuid ? uuid : UUID.fromString(value.toString()));
            }
        }
        return new LinkRequest(rs.getString("token_hash"), rs.getObject("company_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getString("external_id"), rs.getString("display_name"),
                rs.getString("team_id"), rs.getString("team_name"), heldIds,
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant(), instant(rs, "consumed_at"),
                rs.getString("outcome"));
    }

    // ---- chat_preference (§3.6) -----------------------------------------------------------------------------------

    Optional<UUID> defaultAgent(UUID principalId) {
        return jdbc.sql("SELECT default_agent_id FROM chat_preference WHERE principal_id = ?")
            .param(principalId)
            .query((rs, row) -> Optional.ofNullable(rs.getObject("default_agent_id", UUID.class)))
            .optional()
            .flatMap(value -> value);
    }

    void defaultAgent(UUID companyId, UUID principalId, @Nullable UUID agentId, Instant at) {
        jdbc.sql("""
                INSERT INTO chat_preference (company_id, principal_id, default_agent_id, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (principal_id) DO UPDATE
                   SET default_agent_id = excluded.default_agent_id, updated_at = excluded.updated_at""")
            .params(companyId, principalId, agentId, ts(at))
            .update();
    }

    // ---- chat_setting (§5.2) --------------------------------------------------------------------------------------

    /** The company's rates; {@code version} 0 for the instance's defaults, else the count of the company's changes. */
    record Rates(int messagesPerUserMinute, int messagesPerUserDay, int eventsPerSystemMinute, int version) {
    }

    Optional<Rates> rates(UUID companyId) {
        return jdbc.sql("SELECT messages_per_user_minute, messages_per_user_day, events_per_system_minute, version"
                + " FROM chat_setting WHERE company_id = ?")
            .param(companyId)
            .query((rs, row) -> new Rates(rs.getInt(1), rs.getInt(2), rs.getInt(3), rs.getInt(4)))
            .optional();
    }

    /**
     * Stores the rates when they are still at {@code version} (0: no row yet), under optimistic locking (V060): a new
     * row takes version 1, a changed one the next. Nothing is written and false answered when they moved meanwhile.
     */
    boolean rates(UUID companyId, Rates rates, int version, @Nullable UUID by, Instant at) {
        if (version == 0) {
            // A row that appeared meanwhile is at version 1 or more: DO NOTHING, and the change is refused.
            return jdbc.sql("""
                    INSERT INTO chat_setting (company_id, messages_per_user_minute, messages_per_user_day,
                                              events_per_system_minute, version, updated_at, updated_by)
                    VALUES (?, ?, ?, ?, 1, ?, ?)
                    ON CONFLICT (company_id) DO NOTHING""")
                .params(companyId, rates.messagesPerUserMinute(), rates.messagesPerUserDay(),
                        rates.eventsPerSystemMinute(), ts(at), by)
                .update() == 1;
        }
        return jdbc.sql("""
                UPDATE chat_setting
                   SET messages_per_user_minute = ?, messages_per_user_day = ?, events_per_system_minute = ?,
                       version = version + 1, updated_at = ?, updated_by = ?
                 WHERE company_id = ? AND version = ?""")
            .params(rates.messagesPerUserMinute(), rates.messagesPerUserDay(), rates.eventsPerSystemMinute(), ts(at),
                    by, companyId, version)
            .update() == 1;
    }

    // ---- chat_notice (the inbox's items) --------------------------------------------------------------------------

    record Notice(UUID id, UUID systemId, @Nullable UUID principalId, String kind, Map<String, Object> details,
            Instant createdAt) {
    }

    void notice(UUID id, UUID companyId, UUID systemId, @Nullable UUID principalId, String kind,
            Map<String, Object> details, Instant at, @Nullable Instant expiresAt) {
        jdbc.sql("""
                INSERT INTO chat_notice (id, company_id, system_id, principal_id, kind, details, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?)
                ON CONFLICT DO NOTHING""")
            .params(id, companyId, systemId, principalId, kind, JSON.writeValueAsString(details), ts(at),
                    expiresAt != null ? ts(expiresAt) : null)
            .update();
    }

    void clearNotices(UUID systemId, String kind) {
        jdbc.sql("DELETE FROM chat_notice WHERE system_id = ? AND kind = ?").params(systemId, kind).update();
    }

    /** The member's own items, and the admins' when {@code admin}; unexpired, oldest first. */
    List<Notice> notices(UUID companyId, @Nullable UUID principalId, boolean admin, Instant now) {
        return jdbc.sql("""
                SELECT id, system_id, principal_id, kind, details, created_at FROM chat_notice
                 WHERE company_id = ? AND (expires_at IS NULL OR expires_at > ?)
                   AND ((principal_id IS NOT NULL AND principal_id = ?) OR (principal_id IS NULL AND ?))
                 ORDER BY created_at, id LIMIT 100""")
            .params(companyId, ts(now), principalId, admin)
            .query((rs, row) -> new Notice(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                    rs.getObject("principal_id", UUID.class), rs.getString("kind"), map(rs.getString("details")),
                    rs.getObject("created_at", OffsetDateTime.class).toInstant()))
            .list();
    }

    // ---- chat_post (what the bot posted) --------------------------------------------------------------------------

    record Post(UUID id, UUID systemId, @Nullable UUID conversationId, @Nullable UUID decisionId,
            @Nullable UUID bindingId, String purpose, String channelId, @Nullable String threadId, String externalId) {
    }

    void post(UUID companyId, Post post, Instant at) {
        jdbc.sql("""
                INSERT INTO chat_post (id, company_id, system_id, conversation_id, decision_id, binding_id, purpose,
                                       channel_id, thread_id, external_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(post.id(), companyId, post.systemId(), post.conversationId(), post.decisionId(), post.bindingId(),
                    post.purpose(), post.channelId(), post.threadId(), post.externalId(), ts(at))
            .update();
    }

    private static final String POST_COLUMNS = "id, system_id, conversation_id, decision_id, binding_id, purpose,"
            + " channel_id, thread_id, external_id";

    List<Post> postsOfDecision(UUID decisionId) {
        return jdbc.sql("SELECT " + POST_COLUMNS + " FROM chat_post WHERE decision_id = ? ORDER BY created_at")
            .param(decisionId)
            .query(ChatStore::post)
            .list();
    }

    List<Post> postsOfBinding(UUID bindingId) {
        return jdbc.sql("SELECT " + POST_COLUMNS + " FROM chat_post WHERE binding_id = ? ORDER BY created_at")
            .param(bindingId)
            .query(ChatStore::post)
            .list();
    }

    Optional<Post> postByExternal(UUID systemId, String externalId) {
        return jdbc.sql("SELECT " + POST_COLUMNS + " FROM chat_post WHERE system_id = ? AND external_id = ?"
                + " ORDER BY created_at DESC LIMIT 1")
            .params(systemId, externalId)
            .query(ChatStore::post)
            .optional();
    }

    private static Post post(ResultSet rs, int row) throws SQLException {
        return new Post(rs.getObject("id", UUID.class), rs.getObject("system_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getObject("decision_id", UUID.class),
                rs.getObject("binding_id", UUID.class), rs.getString("purpose"), rs.getString("channel_id"),
                rs.getString("thread_id"), rs.getString("external_id"));
    }

    // ---- retention (the package 6 contract, §5.9: fixed elsewhere) ---------------------------------------------------

    /** Deletes the company's events that arrived before the cutoff, whatever became of them; answers how many. */
    long deleteEventsBefore(UUID companyId, Instant cutoff) {
        return jdbc.sql("DELETE FROM chat_event WHERE company_id = ? AND received_at < ?")
            .params(companyId, ts(cutoff))
            .update();
    }

    /** Deletes the company's chat users last seen before the cutoff; answers how many. */
    long deleteUsersSeenBefore(UUID companyId, Instant cutoff) {
        return jdbc.sql("DELETE FROM chat_user WHERE company_id = ? AND last_seen_at < ?")
            .params(companyId, ts(cutoff))
            .update();
    }

    // ---- an outside identity's erasure (the package 6 contract, §5.9) -------------------------------------------------

    /** The events the chat user sent on the system; parameters: the company, the system, their id there. */
    private static final String EVENTS_OF = "chat_event WHERE company_id = ? AND system_id = ? AND sender = ?";

    /** The chat user's row on the system; likewise. */
    private static final String USER_OF = "chat_user WHERE company_id = ? AND system_id = ? AND external_id = ?";

    /** The chat user's sign-in links on the system, pending or consumed; likewise. */
    private static final String LINKS_OF = "chat_link_request WHERE company_id = ? AND system_id = ?"
            + " AND external_id = ?";

    /** Deletes the events the chat user sent on the system, whatever became of them; answers how many. */
    long deleteEventsOf(UUID companyId, UUID systemId, String externalId) {
        return jdbc.sql("DELETE FROM " + EVENTS_OF).params(companyId, systemId, externalId).update();
    }

    /** Deletes the chat user's row on the system; answers how many (0 or 1). */
    long deleteUser(UUID companyId, UUID systemId, String externalId) {
        return jdbc.sql("DELETE FROM " + USER_OF).params(companyId, systemId, externalId).update();
    }

    /** Deletes the chat user's sign-in links on the system, pending or consumed; answers how many. */
    long deleteLinksOf(UUID companyId, UUID systemId, String externalId) {
        return jdbc.sql("DELETE FROM " + LINKS_OF).params(companyId, systemId, externalId).update();
    }

    /** How many events {@link #deleteEventsOf} would delete now. */
    long countEventsOf(UUID companyId, UUID systemId, String externalId) {
        return countOf(EVENTS_OF, companyId, systemId, externalId);
    }

    /** How many rows {@link #deleteUser} would delete now. */
    long countUser(UUID companyId, UUID systemId, String externalId) {
        return countOf(USER_OF, companyId, systemId, externalId);
    }

    /** How many links {@link #deleteLinksOf} would delete now. */
    long countLinksOf(UUID companyId, UUID systemId, String externalId) {
        return countOf(LINKS_OF, companyId, systemId, externalId);
    }

    private long countOf(String rows, UUID companyId, UUID systemId, String externalId) {
        return jdbc.sql("SELECT count(*) FROM " + rows)
            .params(companyId, systemId, externalId)
            .query(Long.class)
            .single();
    }

    // ---- chat_socket (Socket Mode) --------------------------------------------------------------------------------

    /** Takes or keeps a Socket Mode slot for the holder until {@code until}: false while another replica holds it. */
    boolean leaseSocket(UUID companyId, UUID systemId, int slot, String holder, Instant now, Instant until) {
        return jdbc.sql("""
                INSERT INTO chat_socket (company_id, system_id, slot, holder, lease_until) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (system_id, slot) DO UPDATE SET holder = excluded.holder, lease_until = excluded.lease_until
                 WHERE chat_socket.holder = excluded.holder OR chat_socket.lease_until < ?""")
            .params(companyId, systemId, slot, holder, ts(until), ts(now))
            .update() == 1;
    }

    void releaseSocket(UUID systemId, int slot, String holder) {
        jdbc.sql("DELETE FROM chat_socket WHERE system_id = ? AND slot = ? AND holder = ?")
            .params(systemId, slot, holder)
            .update();
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    static Map<String, Object> map(@Nullable String json) {
        if (json == null) {
            return Map.of();
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> value = JSON.readValue(json, LinkedHashMap.class);
        return value != null ? value : Map.of();
    }

    static JsonNode tree(String json) {
        return JSON.readTree(json);
    }

    static @Nullable String cut(@Nullable String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    static List<String> list(@Nullable Object value) {
        if (value instanceof List<?> items) {
            return items.stream().map(String::valueOf).toList();
        }
        return value instanceof String[] array ? Arrays.asList(array) : List.of();
    }
}
