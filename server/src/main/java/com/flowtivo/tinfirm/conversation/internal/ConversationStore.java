package com.flowtivo.tinfirm.conversation.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.runner.RuntimeKind;

/**
 * SQL access to {@code conversation}, {@code conversation_key_counter} and {@code message} (V005), under row-level
 * security: the conversations of the package 3 contract, §3.8 and §3.10.
 */
@Repository
class ConversationStore {

    private static final String COLUMNS = "id, company_id, agent_id, key, title, place, origin, person_principal_id,"
            + " subject_system_id, subject_key, subject_url, subject_title, runtime, payer_principal_id, model_access,"
            + " subscription_host_id, subscription_label, workspace::text AS workspace, granted_hosts, state,"
            + " ended_reason, ended_at, ended_by, turn_status, waiting_on, waiting_reason, waiting_since, turn_count,"
            + " active_run_id, last_run_id, last_turn_at, result_outcome, result_summary,"
            + " result_next_steps::text AS result_next_steps, result_open_questions::text AS result_open_questions,"
            + " failure_class, failure_cause, created_at, updated_at, created_by, content_purged_at, erased_at,"
            + " trigger_id, author_principal_id, chain_depth, place_ref::text AS place_ref";

    private static final String MESSAGE_COLUMNS = "id, company_id, conversation_id, kind, author_principal_id, body,"
            + " answers_open_questions, run_id, decision_id, unanswered, created_at, purged_at, external_id,"
            + " external_author ->> 'name' AS external_name, external_author ->> 'login' AS external_login, steering";

    /**
     * What a purged message shows in its body's place (the package 6 contract, §3.7, §5.9): to the API, which also
     * names when ({@code purgedAt}), and to the next turn's prompt of an open conversation, where an erasure replaced
     * the words at once.
     */
    static final String PURGED_BODY = "[This message was removed.]";

    /** The first key of the advisory lock of a member's starts through AI clients ("MCPS"). */
    static final int CLIENT_START_LOCK = 0x4D435053;

    private final JdbcClient jdbc;

    /** Each write below signals the conversation's live streams once its transaction commits. */
    private final ConversationSignals signals;

    ConversationStore(JdbcClient jdbc, ConversationSignals signals) {
        this.jdbc = jdbc;
        this.signals = signals;
    }

    /**
     * A conversation row: what {@link ConversationInfo} shows, and the latest turn's result and failure.
     *
     * @param resultOutcome {@code COMPLETED} or {@code BLOCKED}; null without a result
     */
    record ConversationRow(ConversationInfo info, @Nullable String resultOutcome, @Nullable String resultSummary,
            List<String> resultNextSteps, List<String> resultOpenQuestions, @Nullable String failureClass,
            @Nullable String failureCause, @Nullable UUID subscriptionHostId, @Nullable UUID createdBy,
            @Nullable Instant contentPurgedAt, @Nullable Instant erasedAt, @Nullable String placeRef) {

        UUID id() {
            return info.id();
        }

        UUID companyId() {
            return info.companyId();
        }
    }

    /**
     * A message row.
     *
     * @param body         {@link #PURGED_BODY} once purged
     * @param purgedAt     when retention or an erasure removed the body; null while it is kept
     * @param externalId   an {@code EXTERNAL} message's comment id at its system
     * @param externalName an {@code EXTERNAL} message's author's name (or login) at its system
     * @param steering     a person's message written while a turn ran: {@code SENDING} while it goes to that turn,
     *                     {@code DELIVERED} once the turn took it; null otherwise
     */
    record MessageRow(UUID id, UUID companyId, UUID conversationId, String kind, @Nullable UUID authorPrincipalId,
            String body, boolean answersOpenQuestions, @Nullable UUID runId, @Nullable UUID decisionId,
            boolean unanswered, Instant createdAt, @Nullable Instant purgedAt, @Nullable String externalId,
            @Nullable String externalName, @Nullable String steering) {

        /** A person's message the running turn took (steering). */
        static final String DELIVERED = "DELIVERED";

        /** A person's message on its way to the running turn (steering). */
        static final String SENDING = "SENDING";

        MessageRow(UUID id, UUID companyId, UUID conversationId, String kind, @Nullable UUID authorPrincipalId,
                String body, boolean answersOpenQuestions, @Nullable UUID runId, @Nullable UUID decisionId,
                boolean unanswered, Instant createdAt) {
            this(id, companyId, conversationId, kind, authorPrincipalId, body, answersOpenQuestions, runId,
                    decisionId, unanswered, createdAt, null, null, null, null);
        }

        /** A person's message the running turn took while it worked. */
        boolean steered() {
            return DELIVERED.equals(steering);
        }

        boolean external() {
            return "EXTERNAL".equals(kind);
        }

        boolean person() {
            return "PERSON".equals(kind);
        }

        boolean waiting() {
            return person() && runId == null && !unanswered;
        }
    }

    /**
     * What a caller sees of the company's conversations (the package 3 contract, §5.10; the package 4 contract,
     * §5.13): every personal conversation (OWNER) or their own, and the conversations of the triggers whose audience
     * they are in (every trigger for OWNER and ADMIN), and those they started as a trigger's author.
     *
     * @param principalId     the caller's principal: their personal conversations, and those they are the author of
     * @param allTriggers     every trigger's conversations
     * @param triggerIds      the triggers whose conversations they see
     * @param startedAsAuthor also the trigger's conversations they started as its author (in the app; not through a
     *                        client, where they see those of the triggers they act on only)
     */
    record Scope(boolean allPersonal, @Nullable UUID principalId, boolean allTriggers, Set<UUID> triggerIds,
            boolean startedAsAuthor) {

        Scope {
            triggerIds = Set.copyOf(triggerIds);
        }

        boolean none() {
            return !allPersonal && principalId == null && !allTriggers && triggerIds.isEmpty();
        }
    }

    /**
     * The filters of {@code GET /conversations}.
     *
     * @param personPrincipalId OWNER's filter by person
     * @param triggerId         a trigger's conversations
     */
    record Filter(Scope scope, @Nullable UUID personPrincipalId, @Nullable UUID triggerId, @Nullable UUID agentId,
            @Nullable UUID subjectSystemId, @Nullable String subjectKey, ConversationInfo.@Nullable State state,
            ConversationInfo.@Nullable WaitingOn waitingOn) {
    }

    /** A subject of the conversations a caller sees, with counts. */
    record SubjectRow(@Nullable UUID systemId, String key, @Nullable String title, @Nullable String url,
            int conversations, int open, @Nullable Instant lastActivityAt) {
    }

    // ---- keys ------------------------------------------------------------------------------------------------------

    /** The next n of {@code {base}-{n}}, advanced under the counter row's lock: keys are never reused. */
    long nextKeyNumber(UUID companyId, String base) {
        return jdbc.sql("""
                INSERT INTO conversation_key_counter (company_id, base, next) VALUES (?, ?, 2)
                ON CONFLICT (company_id, base) DO UPDATE SET next = conversation_key_counter.next + 1
                RETURNING next - 1""")
            .params(companyId, base)
            .query(Long.class)
            .single();
    }

    // ---- conversations ---------------------------------------------------------------------------------------------

    void insert(ConversationInfo conversation, @Nullable UUID subscriptionHostId, @Nullable UUID createdBy) {
        insert(conversation, subscriptionHostId, createdBy, null);
    }

    /**
     * Inserts the conversation; a trigger's with its place's reference (V011: {@code TRACKER_ISSUE}'s
     * {@code {systemId, subjectId, key, kind, url}}, {@code SCHEDULE}'s {@code {triggerId, firingId, dueAt}}), and a
     * direct message's (V018: {@code CHAT_THREAD}'s {@code {systemId, kind, channelId, threadId, serviceUrl?}}).
     */
    void insert(ConversationInfo conversation, @Nullable UUID subscriptionHostId, @Nullable UUID createdBy,
            @Nullable String placeRef) {
        ConversationInfo.Subject subject = conversation.subject();
        jdbc.sql("""
                INSERT INTO conversation (id, company_id, agent_id, key, title, place, origin, person_principal_id,
                                          subject_system_id, subject_key, subject_url, subject_title, runtime,
                                          payer_principal_id, model_access, subscription_host_id, subscription_label,
                                          workspace, granted_hosts, state, waiting_on, turn_count, created_at,
                                          updated_at, created_by, trigger_id, author_principal_id, chain_depth,
                                          place_ref)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS varchar(260)[]),
                        'OPEN', 'NONE', 0, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))""")
            .params(conversation.id(), conversation.companyId(), conversation.agentId(), conversation.key(),
                    conversation.title(), conversation.place().name(), conversation.origin().name(),
                    conversation.personPrincipalId(), subject != null ? subject.systemId() : null,
                    subject != null ? subject.key() : null, subject != null ? subject.url() : null,
                    subject != null ? subject.title() : null, conversation.runtime().name(),
                    conversation.payerPrincipalId(),
                    conversation.modelAccess() instanceof ModelAccessChoice.Subscription ? "SUBSCRIPTION" : "BUDGET",
                    subscriptionHostId, conversation.subscriptionLabel(),
                    ConversationJson.workspace(conversation.workspace()),
                    conversation.grantedHosts().toArray(String[]::new), ts(conversation.createdAt()),
                    ts(conversation.updatedAt()), createdBy, conversation.triggerId(), conversation.authorPrincipalId(),
                    conversation.chainDepth(), placeRef)
            .update();
    }

    Optional<ConversationRow> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversation WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(ConversationStore::row)
            .optional();
    }

    /** The id of the conversation with the key in the company. */
    Optional<UUID> idOfKey(UUID companyId, String key) {
        return jdbc.sql("SELECT id FROM conversation WHERE company_id = ? AND key = ?")
            .params(companyId, key)
            .query(UUID.class)
            .optional();
    }

    /**
     * Serialises a member's starts through AI clients until the transaction ends, on every replica (the package 6
     * contract, open point 14): a transaction-scoped advisory lock on the member's principal, taken before any row
     * lock of the start, so it orders before every lock of CONVENTIONS §10 and waits on nothing but another start of
     * the same member.
     */
    void lockClientStarts(UUID personPrincipalId) {
        jdbc.sql("SELECT pg_advisory_xact_lock(?, hashtext(?))")
            .params(CLIENT_START_LOCK, personPrincipalId.toString())
            .query()
            .singleRow();
    }

    /** When the member's newest {@code CLIENT} conversations since {@code since} were started, newest first. */
    List<Instant> clientStartsSince(UUID companyId, UUID personPrincipalId, Instant since, int limit) {
        return jdbc.sql("""
                SELECT created_at FROM conversation
                 WHERE company_id = ? AND person_principal_id = ? AND origin = 'CLIENT' AND created_at > ?
                 ORDER BY created_at DESC
                 LIMIT ?""")
            .params(companyId, personPrincipalId, ts(since), limit)
            .query((rs, row) -> instant(rs, "created_at"))
            .list();
    }

    /**
     * The conversation, locked: its turns, messages, bindings and state change one at a time. The first lock of a
     * transaction that changes a conversation, before its runs' rows, its bindings and its first audit record
     * (CONVENTIONS §10); {@code FOR NO KEY UPDATE}, so that the foreign keys' checks of the rows that name it (a run,
     * a message) never wait on it: no conversation's id changes and none is deleted.
     */
    Optional<ConversationRow> lock(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversation WHERE company_id = ? AND id = ? FOR NO KEY UPDATE")
            .params(companyId, id)
            .query(ConversationStore::row)
            .optional();
    }

    /** Conversations by (updated_at desc, id desc), after the given position. */
    List<ConversationRow> page(UUID companyId, Filter filter, @Nullable Instant afterUpdatedAt, @Nullable UUID afterId,
            int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM conversation WHERE company_id = ?");
        List<Object> params = new ArrayList<>(List.of(companyId));
        where(sql, params, filter);
        if (afterUpdatedAt != null && afterId != null) {
            sql.append(" AND (updated_at, id) < (?, ?)");
            params.add(ts(afterUpdatedAt));
            params.add(afterId);
        }
        sql.append(" ORDER BY updated_at DESC, id DESC LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(ConversationStore::row).list();
    }

    /** The subjects of the conversations the scope sees, by (key, system), after the given position. */
    List<SubjectRow> subjects(UUID companyId, Scope scope, @Nullable UUID systemId, @Nullable String afterKey,
            @Nullable UUID afterSystemId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT subject_system_id, subject_key, max(subject_title) AS title, max(subject_url) AS url,
                       count(*) AS conversations, count(*) FILTER (WHERE state = 'OPEN') AS open,
                       max(updated_at) AS last_activity
                  FROM conversation
                 WHERE company_id = ? AND subject_key IS NOT NULL""");
        List<Object> params = new ArrayList<>(List.of(companyId));
        if (systemId != null) {
            sql.append(" AND subject_system_id = ?");
            params.add(systemId);
        }
        scope(sql, params, scope);
        sql.append(" GROUP BY subject_system_id, subject_key");
        if (afterKey != null && afterSystemId != null) {
            sql.append(" HAVING (subject_key, coalesce(subject_system_id, '00000000-0000-0000-0000-000000000000'::uuid))"
                    + " > (?, ?)");
            params.add(afterKey);
            params.add(afterSystemId);
        }
        sql.append(" ORDER BY subject_key,"
                + " coalesce(subject_system_id, '00000000-0000-0000-0000-000000000000'::uuid) LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query((rs, rowNum) -> new SubjectRow(
                rs.getObject("subject_system_id", UUID.class), rs.getString("subject_key"), rs.getString("title"),
                rs.getString("url"), rs.getInt("conversations"), rs.getInt("open"),
                nullableInstant(rs, "last_activity"))).list();
    }

    /** The open conversations of the agent, or of the person. */
    List<ConversationRow> openOfAgent(UUID companyId, UUID agentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversation WHERE company_id = ? AND agent_id = ?"
                + " AND state = 'OPEN' ORDER BY created_at, id")
            .params(companyId, agentId)
            .query(ConversationStore::row)
            .list();
    }

    List<ConversationRow> openOfPerson(UUID companyId, UUID personPrincipalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversation WHERE company_id = ? AND person_principal_id = ?"
                + " AND state = 'OPEN' ORDER BY created_at, id")
            .params(companyId, personPrincipalId)
            .query(ConversationStore::row)
            .list();
    }

    void rename(UUID companyId, UUID id, String title, Instant now) {
        jdbc.sql("UPDATE conversation SET title = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(title, ts(now), companyId, id)
            .update();
        signals.changed(id, ConversationSignals.Part.STATUS);
    }

    void end(UUID companyId, UUID id, ConversationInfo.EndedReason reason, @Nullable UUID endedBy, Instant now) {
        jdbc.sql("""
                UPDATE conversation SET state = 'ENDED', ended_reason = ?, ended_at = ?, ended_by = ?, updated_at = ?
                 WHERE company_id = ? AND id = ? AND state = 'OPEN'""")
            .params(reason.name(), ts(now), endedBy, ts(now), companyId, id)
            .update();
        signals.changed(id, ConversationSignals.Part.STATUS);
    }

    /**
     * A turn's run was created: the turn's number, its run, and a clean slate for its result; {@code autoRetries}, the
     * automatic retries in a row that the turn is (ADR-0073), 0 for any other.
     */
    void turnStarted(UUID companyId, UUID id, UUID runId, int turnCount, int autoRetries, Instant now) {
        jdbc.sql("""
                UPDATE conversation
                   SET turn_count = ?, active_run_id = ?, last_run_id = ?, last_turn_at = ?, turn_status = 'QUEUED',
                       result_outcome = NULL, result_summary = NULL, result_next_steps = NULL,
                       result_open_questions = NULL, failure_class = NULL, failure_cause = NULL, auto_retries = ?,
                       updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(turnCount, runId, runId, ts(now), autoRetries, ts(now), companyId, id)
            .update();
        signals.changed(id, ConversationSignals.Part.STATUS, ConversationSignals.Part.TURNS);
    }

    /**
     * The version of its agent whose instructions and skills the conversation's latest turn used (V080); null before
     * its first turn.
     */
    @Nullable Integer agentContentVersion(UUID companyId, UUID id) {
        return jdbc.sql("SELECT agent_content_version FROM conversation WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query((rs, n) -> Optional.ofNullable(rs.getObject("agent_content_version", Integer.class)))
            .list()
            .stream()
            .flatMap(Optional::stream)
            .findFirst()
            .orElse(null);
    }

    void setAgentContentVersion(UUID companyId, UUID id, int version) {
        jdbc.sql("UPDATE conversation SET agent_content_version = ? WHERE company_id = ? AND id = ?")
            .params(version, companyId, id)
            .update();
    }

    /** How many automatic retries in a row the conversation's latest turn is (ADR-0073); 0 for any other turn. */
    int automaticRetries(UUID companyId, UUID id) {
        return jdbc.sql("SELECT auto_retries FROM conversation WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(Integer.class)
            .optional()
            .orElse(0);
    }

    /** A turn that failed before any run: the runtime or the subscription is gone. */
    void turnFailedWithoutRun(UUID companyId, UUID id, String failureClass, String cause, Instant now) {
        jdbc.sql("""
                UPDATE conversation
                   SET turn_status = 'FAILED', result_outcome = NULL, result_summary = NULL, result_next_steps = NULL,
                       result_open_questions = NULL, failure_class = ?, failure_cause = ?, last_turn_at = ?,
                       updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(failureClass, cut(cause, 2000), ts(now), ts(now), companyId, id)
            .update();
        signals.changed(id, ConversationSignals.Part.STATUS, ConversationSignals.Part.TURNS);
    }

    /** The active run ended: its result or failure is the conversation's latest. */
    void turnEnded(UUID companyId, UUID id, UUID runId, ConversationInfo.TurnStatus status,
            @Nullable String resultOutcome, @Nullable String summary, List<String> nextSteps,
            List<String> openQuestions, @Nullable String failureClass, @Nullable String failureCause, Instant now) {
        jdbc.sql("""
                UPDATE conversation
                   SET active_run_id = CASE WHEN active_run_id = ? THEN NULL ELSE active_run_id END, turn_status = ?,
                       result_outcome = ?, result_summary = ?, result_next_steps = CAST(? AS jsonb),
                       result_open_questions = CAST(? AS jsonb), failure_class = ?, failure_cause = ?, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(runId, status.name(), resultOutcome, summary,
                    resultOutcome != null ? ConversationJson.strings(nextSteps) : null,
                    resultOutcome != null ? ConversationJson.strings(openQuestions) : null, failureClass,
                    cut(failureCause, 2000), ts(now), companyId, id)
            .update();
        signals.changed(id, ConversationSignals.Part.STATUS, ConversationSignals.Part.TURNS);
    }

    /** A lost run its host still ran is the conversation's active run again. */
    void turnResumed(UUID companyId, UUID id, UUID runId, Instant now) {
        jdbc.sql("""
                UPDATE conversation SET active_run_id = ?, failure_class = NULL, failure_cause = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(runId, ts(now), companyId, id)
            .update();
        signals.changed(id, ConversationSignals.Part.STATUS, ConversationSignals.Part.TURNS);
    }

    /** What the conversation waits on; {@code since} moves only when the kind or the reason changes. */
    void updateWaiting(UUID companyId, UUID id, ConversationInfo.@Nullable TurnStatus turnStatus,
            ConversationInfo.WaitingOn on, @Nullable String reason, Instant now) {
        if (jdbc.sql("""
                UPDATE conversation
                   SET turn_status = ?, waiting_on = ?, waiting_reason = ?,
                       waiting_since = CASE WHEN ? = 'NONE' THEN NULL
                                            WHEN waiting_on = ? AND waiting_reason IS NOT DISTINCT FROM ?
                                                 AND waiting_since IS NOT NULL THEN waiting_since
                                            ELSE ? END
                 WHERE company_id = ? AND id = ?
                   AND (turn_status IS DISTINCT FROM ? OR waiting_on <> ? OR waiting_reason IS DISTINCT FROM ?)""")
            .params(turnStatus != null ? turnStatus.name() : null, on.name(), reason, on.name(), on.name(), reason,
                    ts(now), companyId, id, turnStatus != null ? turnStatus.name() : null, on.name(), reason)
            .update() > 0) {
            signals.changed(id, ConversationSignals.Part.STATUS);
        }
    }

    void updateWorkspace(UUID companyId, UUID id, List<ConversationInfo.WorkspaceEntry> workspace, Instant now) {
        jdbc.sql("UPDATE conversation SET workspace = CAST(? AS jsonb), updated_at = ? WHERE company_id = ? AND id = ?")
            .params(ConversationJson.workspace(workspace), ts(now), companyId, id)
            .update();
        signals.changed(id, ConversationSignals.Part.WORKSPACE);
    }

    void updateGrantedHosts(UUID companyId, UUID id, List<String> hosts, Instant now) {
        jdbc.sql("""
                UPDATE conversation SET granted_hosts = CAST(? AS varchar(260)[]), updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(hosts.toArray(String[]::new), ts(now), companyId, id)
            .update();
        signals.changed(id, ConversationSignals.Part.WORKSPACE);
    }

    void touch(UUID companyId, UUID id, Instant now) {
        jdbc.sql("UPDATE conversation SET updated_at = ? WHERE company_id = ? AND id = ?")
            .params(ts(now), companyId, id)
            .update();
    }

    // ---- messages --------------------------------------------------------------------------------------------------

    void insertMessage(MessageRow message) {
        jdbc.sql("""
                INSERT INTO message (id, company_id, conversation_id, kind, author_principal_id, body,
                                     answers_open_questions, run_id, decision_id, unanswered, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(message.id(), message.companyId(), message.conversationId(), message.kind(),
                    message.authorPrincipalId(), message.body(), message.answersOpenQuestions(), message.runId(),
                    message.decisionId(), message.unanswered(), ts(message.createdAt()))
            .update();
        signals.changed(message.conversationId(), ConversationSignals.Part.MESSAGES);
    }

    /**
     * An {@code EXTERNAL} message: a comment on the conversation's place (the package 4 contract, §3.6); none when the
     * conversation holds that comment already (the unique {@code (conversation_id, external_id)}).
     *
     * @param authorJson {@code {id, login, name}} at the system
     */
    boolean insertExternal(UUID id, UUID companyId, UUID conversationId, @Nullable UUID authorPrincipalId,
            String body, String externalId, String authorJson, boolean answersOpenQuestions, Instant createdAt) {
        boolean inserted = jdbc.sql("""
                INSERT INTO message (id, company_id, conversation_id, kind, author_principal_id, body,
                                     answers_open_questions, unanswered, created_at, external_id, external_author)
                VALUES (?, ?, ?, 'EXTERNAL', ?, ?, ?, false, ?, ?, CAST(? AS jsonb))
                ON CONFLICT (conversation_id, external_id) DO NOTHING""")
            .params(id, companyId, conversationId, authorPrincipalId, body, answersOpenQuestions, ts(createdAt),
                    externalId, authorJson)
            .update() == 1;
        if (inserted) {
            signals.changed(conversationId, ConversationSignals.Part.MESSAGES);
        }
        return inserted;
    }

    /** The conversation's message of a comment at its system. */
    Optional<UUID> externalMessage(UUID companyId, UUID conversationId, String externalId) {
        return jdbc.sql("SELECT id FROM message WHERE company_id = ? AND conversation_id = ? AND external_id = ?")
            .params(companyId, conversationId, externalId)
            .query(UUID.class)
            .optional();
    }

    /** The conversation's messages, oldest first. */
    List<MessageRow> messages(UUID companyId, UUID conversationId) {
        return jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM message WHERE company_id = ? AND conversation_id = ?"
                + " ORDER BY created_at, id")
            .params(companyId, conversationId)
            .query(ConversationStore::message)
            .list();
    }

    /** The newest messages, oldest first. */
    List<MessageRow> recentMessages(UUID companyId, UUID conversationId, int limit) {
        List<MessageRow> newest = jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM message"
                + " WHERE company_id = ? AND conversation_id = ? ORDER BY created_at DESC, id DESC LIMIT ?")
            .params(companyId, conversationId, limit)
            .query(ConversationStore::message)
            .list();
        List<MessageRow> oldestFirst = new ArrayList<>(newest);
        java.util.Collections.reverse(oldestFirst);
        return oldestFirst;
    }

    long countMessages(UUID companyId, UUID conversationId) {
        return jdbc.sql("SELECT count(*) FROM message WHERE company_id = ? AND conversation_id = ?")
            .params(companyId, conversationId)
            .query(Long.class)
            .single();
    }

    /** The person's messages that wait for the next turn, oldest first. */
    List<MessageRow> waitingMessages(UUID companyId, UUID conversationId) {
        return jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM message WHERE company_id = ? AND conversation_id = ?"
                + " AND kind = 'PERSON' AND run_id IS NULL AND NOT unanswered ORDER BY created_at, id")
            .params(companyId, conversationId)
            .query(ConversationStore::message)
            .list();
    }

    /** The person's messages a run took: its request. */
    List<MessageRow> messagesOfRun(UUID companyId, UUID runId) {
        return jdbc.sql("SELECT " + MESSAGE_COLUMNS + " FROM message WHERE company_id = ? AND run_id = ?"
                + " AND kind = 'PERSON' ORDER BY created_at, id")
            .params(companyId, runId)
            .query(ConversationStore::message)
            .list();
    }

    /** The turn's run takes the waiting messages, also those on their way to the turn before it (steering). */
    int takeWaiting(UUID companyId, UUID conversationId, UUID runId) {
        int taken = jdbc.sql("""
                UPDATE message SET run_id = ?, steering = NULL
                 WHERE company_id = ? AND conversation_id = ? AND kind = 'PERSON' AND run_id IS NULL AND NOT unanswered""")
            .params(runId, companyId, conversationId)
            .update();
        if (taken > 0) {
            signals.changed(conversationId, ConversationSignals.Part.MESSAGES);
        }
        return taken;
    }

    /**
     * A retry takes the request of the turn it retries, with the messages that turn took while it worked: the retry's
     * request from the start.
     */
    int retake(UUID companyId, UUID fromRunId, UUID toRunId) {
        return jdbc.sql("""
                UPDATE message SET run_id = ?, steering = NULL
                 WHERE company_id = ? AND run_id = ? AND kind = 'PERSON'""")
            .params(toRunId, companyId, fromRunId)
            .update();
    }

    /** The waiting messages stay unanswered: a cancel, an end. */
    int dropWaiting(UUID companyId, UUID conversationId) {
        int dropped = jdbc.sql("""
                UPDATE message SET unanswered = true, steering = NULL
                 WHERE company_id = ? AND conversation_id = ? AND kind = 'PERSON' AND run_id IS NULL AND NOT unanswered""")
            .params(companyId, conversationId)
            .update();
        if (dropped > 0) {
            signals.changed(conversationId, ConversationSignals.Part.MESSAGES);
        }
        return dropped;
    }

    /** A waiting person's message goes to the running turn (steering): {@code SENDING} until the host answers. */
    void steerSending(UUID companyId, UUID conversationId, UUID messageId) {
        int marked = jdbc.sql("""
                UPDATE message SET steering = 'SENDING'
                 WHERE company_id = ? AND id = ? AND kind = 'PERSON' AND run_id IS NULL AND NOT unanswered""")
            .params(companyId, messageId)
            .update();
        if (marked > 0) {
            signals.changed(conversationId, ConversationSignals.Part.MESSAGES);
        }
    }

    /**
     * The running turn took a message on its way to it: the message is that run's, {@code DELIVERED}. False when the
     * message is no longer on its way (a turn took it, it stayed unanswered, or it came back to wait).
     */
    boolean steerDelivered(UUID companyId, UUID conversationId, UUID messageId, UUID runId) {
        boolean delivered = jdbc.sql("""
                UPDATE message SET run_id = ?, steering = 'DELIVERED'
                 WHERE company_id = ? AND conversation_id = ? AND id = ? AND kind = 'PERSON' AND run_id IS NULL
                   AND NOT unanswered AND steering = 'SENDING'""")
            .params(runId, companyId, conversationId, messageId)
            .update() == 1;
        if (delivered) {
            signals.changed(conversationId, ConversationSignals.Part.MESSAGES);
        }
        return delivered;
    }

    /**
     * Messages on their way to the running turn that it did not take, or will not answer for: they wait for the next
     * turn as any other. {@code messageId} null: every such message of the conversation (the turn ended).
     */
    int steerReturned(UUID companyId, UUID conversationId, @Nullable UUID messageId) {
        int returned = jdbc.sql("""
                UPDATE message SET steering = NULL
                 WHERE company_id = ? AND conversation_id = ? AND steering = 'SENDING'
                   AND (CAST(? AS uuid) IS NULL OR id = ?)""")
            .params(companyId, conversationId, messageId, messageId)
            .update();
        if (returned > 0) {
            signals.changed(conversationId, ConversationSignals.Part.MESSAGES);
        }
        return returned;
    }

    // ---- rows ------------------------------------------------------------------------------------------------------

    static void scope(StringBuilder sql, List<Object> params, Scope scope) {
        sql.append(" AND ((trigger_id IS NULL AND (");
        if (scope.allPersonal()) {
            sql.append("true");
        }
        else {
            sql.append("person_principal_id = ?");
            params.add(scope.principalId());
        }
        sql.append(")) OR (trigger_id IS NOT NULL AND (");
        if (scope.allTriggers()) {
            sql.append("true");
        }
        else {
            sql.append("trigger_id = ANY (CAST(? AS uuid[]))");
            params.add(ids(scope.triggerIds()));
            if (scope.startedAsAuthor()) {
                sql.append(" OR author_principal_id = ?");
                params.add(scope.principalId());
            }
        }
        sql.append(")))");
    }

    private void where(StringBuilder sql, List<Object> params, Filter filter) {
        scope(sql, params, filter.scope());
        if (filter.personPrincipalId() != null) {
            sql.append(" AND person_principal_id = ?");
            params.add(filter.personPrincipalId());
        }
        if (filter.triggerId() != null) {
            sql.append(" AND trigger_id = ?");
            params.add(filter.triggerId());
        }
        if (filter.agentId() != null) {
            sql.append(" AND agent_id = ?");
            params.add(filter.agentId());
        }
        if (filter.subjectSystemId() != null) {
            sql.append(" AND subject_system_id = ?");
            params.add(filter.subjectSystemId());
        }
        if (filter.subjectKey() != null) {
            sql.append(" AND subject_key = ?");
            params.add(filter.subjectKey());
        }
        if (filter.state() != null) {
            sql.append(" AND state = ?");
            params.add(filter.state().name());
        }
        if (filter.waitingOn() != null) {
            sql.append(" AND waiting_on = ?");
            params.add(filter.waitingOn().name());
        }
    }

    private static ConversationRow row(ResultSet rs, int rowNum) throws SQLException {
        String subjectKey = rs.getString("subject_key");
        ConversationInfo.Subject subject = subjectKey == null ? null
                : new ConversationInfo.Subject(rs.getObject("subject_system_id", UUID.class), subjectKey,
                        rs.getString("subject_url"), rs.getString("subject_title"));
        UUID subscriptionHost = rs.getObject("subscription_host_id", UUID.class);
        String label = rs.getString("subscription_label");
        ModelAccessChoice access = "BUDGET".equals(rs.getString("model_access")) ? ModelAccessChoice.BUDGET
                : subscriptionHost != null && label != null ? new ModelAccessChoice.Subscription(subscriptionHost, label)
                        : null;
        String turnStatus = rs.getString("turn_status");
        String endedReason = rs.getString("ended_reason");
        String key = rs.getString("key");
        String title = rs.getString("title");
        // A purged title shows the key, which the tombstone keeps.
        ConversationInfo info = new ConversationInfo(rs.getObject("id", UUID.class),
                rs.getObject("company_id", UUID.class), rs.getObject("agent_id", UUID.class), key,
                title != null ? title : key, ConversationInfo.Place.valueOf(rs.getString("place")),
                ConversationInfo.Origin.valueOf(rs.getString("origin")),
                rs.getObject("person_principal_id", UUID.class), subject, RuntimeKind.valueOf(rs.getString("runtime")),
                rs.getObject("payer_principal_id", UUID.class), access, label,
                ConversationJson.workspace(rs.getString("workspace")), strings(rs.getArray("granted_hosts")),
                ConversationInfo.State.valueOf(rs.getString("state")),
                endedReason != null ? ConversationInfo.EndedReason.valueOf(endedReason) : null,
                nullableInstant(rs, "ended_at"),
                turnStatus != null ? ConversationInfo.TurnStatus.valueOf(turnStatus) : null,
                ConversationInfo.WaitingOn.valueOf(rs.getString("waiting_on")), rs.getString("waiting_reason"),
                nullableInstant(rs, "waiting_since"), rs.getInt("turn_count"),
                rs.getObject("active_run_id", UUID.class), rs.getObject("last_run_id", UUID.class),
                nullableInstant(rs, "last_turn_at"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getObject("trigger_id", UUID.class), rs.getObject("author_principal_id", UUID.class),
                rs.getInt("chain_depth"));
        return new ConversationRow(info, rs.getString("result_outcome"), rs.getString("result_summary"),
                ConversationJson.strings(rs.getString("result_next_steps")),
                ConversationJson.strings(rs.getString("result_open_questions")), rs.getString("failure_class"),
                rs.getString("failure_cause"), subscriptionHost, rs.getObject("created_by", UUID.class),
                nullableInstant(rs, "content_purged_at"), nullableInstant(rs, "erased_at"),
                rs.getString("place_ref"));
    }

    private static MessageRow message(ResultSet rs, int rowNum) throws SQLException {
        String body = rs.getString("body");
        return new MessageRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getString("kind"),
                rs.getObject("author_principal_id", UUID.class), body != null ? body : PURGED_BODY,
                rs.getBoolean("answers_open_questions"), rs.getObject("run_id", UUID.class),
                rs.getObject("decision_id", UUID.class), rs.getBoolean("unanswered"), instant(rs, "created_at"),
                nullableInstant(rs, "purged_at"), rs.getString("external_id"),
                rs.getString("external_name") != null ? rs.getString("external_name")
                        : rs.getString("external_login"),
                rs.getString("steering"));
    }

    private static List<String> strings(@Nullable Array array) throws SQLException {
        List<String> values = new ArrayList<>();
        if (array != null) {
            for (Object value : (Object[]) array.getArray()) {
                values.add((String) value);
            }
        }
        return values;
    }

    static @Nullable String cut(@Nullable String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        int end = Character.isHighSurrogate(text.charAt(max - 1)) ? max - 1 : max;
        return text.substring(0, end);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    static String[] ids(Collection<UUID> ids) {
        return ids.stream().map(UUID::toString).toArray(String[]::new);
    }
}
