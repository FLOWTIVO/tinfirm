package com.flowtivo.tinfirm.conversation.internal;

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

import com.flowtivo.tinfirm.agent.RepositoryAccess;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL access to {@code decision} (V005, V009): questions and approvals a conversation's person decides. Row-level
 * security limits every statement to the company of the tenant context. A decision is decided once: every change of
 * its status is conditional on {@code status = 'OPEN'} (I16), and the database's trigger refuses the rest.
 */
@Repository
class DecisionStore {

    /** The inbox's condition: an open decision of a conversation that ended is nobody's to decide. */
    private static final String OF_OPEN_CONVERSATION = "AND EXISTS (SELECT 1 FROM conversation c "
            + "WHERE c.id = decision.conversation_id AND c.state = 'OPEN')";

    private static final String COLUMNS = """
            id, company_id, conversation_id, run_id, request_id, kind, question, options, subject::text AS subject,
            reason, approver_principal_id, status, outcome, answer, option, note, scope, access, result,
            decided_by_principal_id, decided_at, cancel_cause, created_at, reported_run_id, call_started_at,
            purged_at, approver_group_id, decided_by_name, decided_by_message_id, approver_admins""";

    private static final TypeReference<List<String>> STRINGS = new TypeReference<>() {
    };

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    private final ConversationSignals signals;

    DecisionStore(JdbcClient jdbc, ConversationSignals signals) {
        this.jdbc = jdbc;
        this.signals = signals;
    }

    enum Kind {
        QUESTION, APPROVAL
    }

    enum Status {
        OPEN, DECIDED, CANCELLED
    }

    enum Outcome {
        ANSWERED, APPROVED, DENIED
    }

    enum Scope {
        CONVERSATION, AGENT
    }

    /**
     * A decision as its row holds it.
     *
     * @param runId         the run that raised it; null for what the server raised itself
     * @param requestId     the platform request or tool call that raised it; null for what the server raised itself
     * @param reportedRunId the turn whose prompt told the agent its outcome (# Answers); null until then
     * @param callStartedAt when an approved tool call was taken to run ({@link #claimCall}); null before, and again
     *                      after a passing failure that called nothing
     */
    record DecisionRow(UUID id, UUID companyId, UUID conversationId, @Nullable UUID runId, @Nullable UUID requestId,
            Kind kind, @Nullable String question, List<String> options, @Nullable DecisionSubject subject,
            @Nullable String reason, @Nullable UUID approverPrincipalId, Status status, @Nullable Outcome outcome,
            @Nullable String answer, @Nullable String option, @Nullable String note, @Nullable Scope scope,
            @Nullable RepositoryAccess access, @Nullable String result, @Nullable UUID decidedByPrincipalId,
            @Nullable Instant decidedAt, @Nullable String cancelCause, Instant createdAt,
            @Nullable UUID reportedRunId, @Nullable Instant callStartedAt, @Nullable Instant purgedAt,
            @Nullable UUID approverGroupId, @Nullable String decidedByName, @Nullable UUID decidedByMessageId,
            boolean approverAdmins) {

        DecisionRow {
            options = List.copyOf(options);
        }

        /**
         * A decision as package 3 raises it: its approver a personal conversation's person, or a trigger's
         * conversation's approver (the package 4 contract, §3.8).
         */
        DecisionRow(UUID id, UUID companyId, UUID conversationId, @Nullable UUID runId, @Nullable UUID requestId,
                Kind kind, @Nullable String question, List<String> options, @Nullable DecisionSubject subject,
                @Nullable String reason, @Nullable UUID approverPrincipalId, Status status, @Nullable Outcome outcome,
                @Nullable String answer, @Nullable String option, @Nullable String note, @Nullable Scope scope,
                @Nullable RepositoryAccess access, @Nullable String result, @Nullable UUID decidedByPrincipalId,
                @Nullable Instant decidedAt, @Nullable String cancelCause, Instant createdAt,
                @Nullable UUID reportedRunId, @Nullable Instant callStartedAt, @Nullable Instant purgedAt) {
            this(id, companyId, conversationId, runId, requestId, kind, question, options, subject, reason,
                    approverPrincipalId, status, outcome, answer, option, note, scope, access, result,
                    decidedByPrincipalId, decidedAt, cancelCause, createdAt, reportedRunId, callStartedAt, purgedAt,
                    null, null, null, false);
        }

        /** The same decision, its approver the given principal or group (a trigger's conversation, §5.8). */
        DecisionRow approvedBy(@Nullable UUID principalId, @Nullable UUID groupId) {
            return new DecisionRow(id, companyId, conversationId, runId, requestId, kind, question, options, subject,
                    reason, principalId, status, outcome, answer, option, note, scope, access, result,
                    decidedByPrincipalId, decidedAt, cancelCause, createdAt, reportedRunId, callStartedAt, purgedAt,
                    groupId, decidedByName, decidedByMessageId, approverAdmins);
        }

        /**
         * The same decision, for the company's Owners and Admins instead of its approver: its only candidates are
         * Viewers, who answer and decide nothing (ADR-0077, decision 5).
         */
        DecisionRow toAdmins() {
            return new DecisionRow(id, companyId, conversationId, runId, requestId, kind, question, options, subject,
                    reason, null, status, outcome, answer, option, note, scope, access, result, decidedByPrincipalId,
                    decidedAt, cancelCause, createdAt, reportedRunId, callStartedAt, purgedAt, null, decidedByName,
                    decidedByMessageId, true);
        }

        boolean open() {
            return status == Status.OPEN;
        }

        boolean approved() {
            return outcome == Outcome.APPROVED;
        }

        /** The subject of an approval; an approval always has one. */
        DecisionSubject requireSubject() {
            if (subject == null) {
                throw new IllegalStateException("Decision " + id + " has no subject");
            }
            return subject;
        }
    }

    /**
     * What {@code GET /decisions} lists: the decisions of the conversations the caller sees ({@code scope}), open
     * only, and with {@code mine} those the caller decides: theirs, their groups', and the questions of the triggers
     * they act on (the package 4 contract, §5.13).
     */
    record Filter(ConversationStore.Scope scope, boolean openOnly, @Nullable Mine mine) {
    }

    /** The decisions a caller decides. */
    record Mine(UUID principalId, Set<UUID> groupIds, Set<UUID> triggerIds, boolean admin) {

        Mine {
            groupIds = Set.copyOf(groupIds);
            triggerIds = Set.copyOf(triggerIds);
        }
    }

    /**
     * Inserts a new OPEN decision; false when the conversation holds a decision of this request id already (a request
     * sent again), which then stays as it is.
     */
    boolean insert(DecisionRow row) {
        return jdbc.sql("""
                INSERT INTO decision (id, company_id, conversation_id, run_id, request_id, kind, question, options,
                                      subject, reason, approver_principal_id, approver_group_id, approver_admins,
                                      status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), CAST(? AS jsonb), ?, ?, ?, ?, 'OPEN', ?)
                ON CONFLICT (conversation_id, request_id) DO NOTHING""")
            .params(row.id(), row.companyId(), row.conversationId(), row.runId(), row.requestId(), row.kind().name(),
                    row.question(), row.kind() == Kind.QUESTION ? JSON.writeValueAsString(row.options()) : null,
                    row.subject() != null ? row.subject().json() : null, row.reason(), row.approverPrincipalId(),
                    row.approverGroupId(), row.approverAdmins(), ts(row.createdAt()))
            .update() == 1;
    }

    Optional<DecisionRow> find(UUID decisionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM decision WHERE id = ?").param(decisionId)
            .query(DecisionStore::row)
            .optional();
    }

    /** The decision, locked for its change. */
    Optional<DecisionRow> lock(UUID decisionId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM decision WHERE id = ? FOR UPDATE").param(decisionId)
            .query(DecisionStore::row)
            .optional();
    }

    /** The conversation's decision of this request id: a request sent again gets the same one. */
    Optional<DecisionRow> byRequest(UUID conversationId, UUID requestId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM decision WHERE conversation_id = ? AND request_id = ?")
            .params(conversationId, requestId)
            .query(DecisionStore::row)
            .optional();
    }

    /** The conversation's OPEN decisions, oldest first, locked. */
    List<DecisionRow> lockOpen(UUID conversationId) {
        return jdbc.sql("SELECT " + COLUMNS + " " + """
                 FROM decision WHERE conversation_id = ? AND status = 'OPEN' ORDER BY created_at, id FOR UPDATE""")
            .param(conversationId)
            .query(DecisionStore::row)
            .list();
    }

    int countOpen(UUID conversationId, Kind kind) {
        return jdbc.sql("SELECT count(*) FROM decision WHERE conversation_id = ? AND status = 'OPEN' AND kind = ?")
            .params(conversationId, kind.name())
            .query(Integer.class)
            .single();
    }

    /** An OPEN approval of the conversation whose subject has this type and this value of {@code field}. */
    Optional<DecisionRow> openApproval(UUID conversationId, DecisionSubject.Type type, String field, String value) {
        return jdbc.sql("SELECT " + COLUMNS + " " + """
                 FROM decision
                WHERE conversation_id = ? AND status = 'OPEN' AND kind = 'APPROVAL'
                  AND subject ->> 'type' = ? AND subject ->> ? = ?
                ORDER BY created_at, id LIMIT 1""")
            .params(conversationId, type.name(), field, value)
            .query(DecisionStore::row)
            .optional();
    }

    /** The repositories the conversation's open requests ask for ({@code requestableRepositories[].pendingRequest}). */
    java.util.Set<UUID> pendingRepositories(UUID conversationId) {
        return new java.util.HashSet<>(jdbc.sql("""
                SELECT CAST(subject ->> 'repositoryId' AS uuid) FROM decision
                 WHERE conversation_id = ? AND status = 'OPEN' AND kind = 'APPROVAL'
                   AND subject ->> 'type' = 'REPOSITORY'""")
            .param(conversationId)
            .query(UUID.class)
            .list());
    }

    /** Decides an OPEN decision (I16): false when it was decided or cancelled already. */
    boolean decide(UUID decisionId, Outcome outcome, @Nullable String answer, @Nullable String option,
            @Nullable String note, @Nullable Scope scope, @Nullable RepositoryAccess access, UUID decidedBy,
            Instant at) {
        return jdbc.sql("""
                UPDATE decision SET status = 'DECIDED', outcome = ?, answer = ?, option = ?, note = ?, scope = ?,
                                    access = ?, decided_by_principal_id = ?, decided_at = ?
                 WHERE id = ? AND status = 'OPEN'""")
            .params(outcome.name(), answer, option, note, scope != null ? scope.name() : null,
                    access != null ? access.name() : null, decidedBy, ts(at), decisionId)
            .update() == 1;
    }

    /** Cancels an OPEN decision: false when it was decided or cancelled already. */
    boolean cancel(UUID decisionId, String cause) {
        return jdbc.sql("UPDATE decision SET status = 'CANCELLED', cancel_cause = ? WHERE id = ? AND status = 'OPEN'")
            .params(cause, decisionId)
            .update() == 1;
    }

    /** The result of an approved call, once it ran. */
    void recordResult(UUID decisionId, String result) {
        jdbc.sql("UPDATE decision SET result = ? WHERE id = ? RETURNING conversation_id")
            .params(result, decisionId)
            .query(UUID.class)
            .optional()
            .ifPresent(conversationId -> signals.changed(conversationId, ConversationSignals.Part.DECISIONS));
    }

    /**
     * Takes an approved call to run, under the decision's lock: false when it has a result or was taken already, by
     * another thread or replica.
     */
    boolean claimCall(UUID decisionId, Instant at) {
        return jdbc.sql("""
                UPDATE decision SET call_started_at = ?
                 WHERE id = ? AND outcome = 'APPROVED' AND result IS NULL AND call_started_at IS NULL""")
            .params(ts(at), decisionId)
            .update() == 1;
    }

    /** Gives an approved call back, after a passing failure that called nothing: a later pass runs it. */
    void releaseCall(UUID decisionId) {
        jdbc.sql("UPDATE decision SET call_started_at = NULL WHERE id = ? AND result IS NULL").param(decisionId)
            .update();
    }

    /**
     * The conversation's decided and cancelled decisions that no turn told yet, and those this run told already (its
     * content asked again: a reassignment), in the order they were settled.
     */
    List<DecisionRow> toReport(UUID conversationId, UUID runId) {
        return jdbc.sql("SELECT " + COLUMNS + " " + """
                 FROM decision
                WHERE conversation_id = ? AND status <> 'OPEN' AND (reported_run_id IS NULL OR reported_run_id = ?)
                ORDER BY coalesce(decided_at, created_at), created_at, id""")
            .params(conversationId, runId)
            .query(DecisionStore::row)
            .list();
    }

    /** Records that the run's prompt told these decisions. */
    void markReported(Collection<UUID> decisionIds, UUID runId) {
        if (decisionIds.isEmpty()) {
            return;
        }
        jdbc.sql("UPDATE decision SET reported_run_id = :run WHERE id IN (:ids) AND reported_run_id IS NULL")
            .param("run", runId)
            .param("ids", decisionIds)
            .update();
    }

    /**
     * A page of the decisions {@code GET /decisions} lists, newest first (keyset on created_at and id): of every
     * conversation of the company, or of one person's.
     */
    List<DecisionRow> page(Filter filter, @Nullable Instant beforeCreatedAt, @Nullable UUID beforeId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM decision WHERE true");
        List<Object> params = new ArrayList<>();
        ConversationStore.Scope scope = filter.scope();
        if (!scope.allPersonal() || !scope.allTriggers()) {
            sql.append(" AND conversation_id IN (SELECT id FROM conversation WHERE true");
            ConversationStore.scope(sql, params, scope);
            sql.append(")");
        }
        if (filter.openOnly()) {
            sql.append(" AND status = 'OPEN'");
        }
        Mine mine = filter.mine();
        if (mine != null) {
            sql.append(" AND (").append(FOR).append(")");
            params.add(mine.principalId());
            params.add(ConversationStore.ids(mine.groupIds()));
            params.add(ConversationStore.ids(mine.triggerIds()));
            params.add(mine.admin());
        }
        if (beforeCreatedAt != null && beforeId != null) {
            sql.append(" AND (created_at, id) < (?, ?)");
            params.add(ts(beforeCreatedAt));
            params.add(beforeId);
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(DecisionStore::row).list();
    }

    /**
     * Whose open decisions: the principal's, its groups' (a trigger's team's approvals), the questions of the
     * conversations of the triggers it acts on (the package 4 contract, §5.13: anyone in the place answers them), and
     * for OWNER and ADMIN those whose only candidates were Viewers (ADR-0077, decision 5).
     */
    private static final String FOR = """
            status = 'OPEN' AND (approver_principal_id = ? OR approver_group_id = ANY (CAST(? AS uuid[]))
                 OR (kind = 'QUESTION' AND approver_principal_id IS NULL AND approver_group_id IS NULL
                     AND conversation_id IN (SELECT c.id FROM conversation c
                                              WHERE c.trigger_id = ANY (CAST(? AS uuid[]))))
                 OR (approver_admins AND ?))""";

    /** The open decisions the principal decides or answers, longest waiting first. */
    List<DecisionRow> openFor(UUID approverPrincipalId, Collection<UUID> groupIds, Collection<UUID> triggerIds,
            boolean admin, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM decision WHERE " + FOR + " " + OF_OPEN_CONVERSATION + " "
                + "ORDER BY created_at, id LIMIT ?")
            .params(approverPrincipalId, ConversationStore.ids(groupIds), ConversationStore.ids(triggerIds), admin,
                    limit)
            .query(DecisionStore::row)
            .list();
    }

    int countOpenFor(UUID approverPrincipalId, Collection<UUID> groupIds, Collection<UUID> triggerIds,
            boolean admin) {
        return jdbc.sql("SELECT count(*) FROM decision WHERE " + FOR + " " + OF_OPEN_CONVERSATION)
            .params(approverPrincipalId, ConversationStore.ids(groupIds), ConversationStore.ids(triggerIds), admin)
            .query(Integer.class)
            .single();
    }

    /**
     * The open approvals of the trigger's conversations without an active run, locked by id: a team's change moves
     * them (the package 4 contract, §5.11), after their conversations' locks.
     */
    List<DecisionRow> lockOpenApprovalsOf(Collection<UUID> conversationIds) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM decision WHERE conversation_id = ANY (CAST(? AS uuid[]))"
                + " AND kind = 'APPROVAL' AND status = 'OPEN' ORDER BY id FOR NO KEY UPDATE")
            .param(ConversationStore.ids(conversationIds))
            .query(DecisionStore::row)
            .list();
    }

    /**
     * The conversations with an open decision that the principals or the groups take part in, for routing them again
     * (a role or a group's members changed, ADR-0077 decision 5): as its approver principal or group, a personal
     * conversation's person, or its trigger's author or team.
     */
    List<UUID> conversationsWithOpenFor(Collection<UUID> principalIds, Collection<UUID> groupIds) {
        if (principalIds.isEmpty() && groupIds.isEmpty()) {
            return List.of();
        }
        String[] principals = ConversationStore.ids(principalIds);
        String[] groups = ConversationStore.ids(groupIds);
        return jdbc.sql("""
                SELECT DISTINCT d.conversation_id
                  FROM decision d
                  JOIN conversation c ON c.id = d.conversation_id AND c.company_id = d.company_id
                  LEFT JOIN trigger t ON t.id = c.trigger_id AND t.company_id = c.company_id
                 WHERE d.status = 'OPEN'
                   AND (d.approver_principal_id = ANY (CAST(? AS uuid[]))
                        OR d.approver_group_id = ANY (CAST(? AS uuid[]))
                        OR c.person_principal_id = ANY (CAST(? AS uuid[]))
                        OR t.author_principal_id = ANY (CAST(? AS uuid[]))
                        OR t.team_group_id = ANY (CAST(? AS uuid[])))
                 ORDER BY d.conversation_id""")
            .params(principals, groups, principals, principals, groups)
            .query(UUID.class)
            .list();
    }

    /** The open decisions of the conversations, locked, as {@link #lockOpenApprovalsOf} does. */
    List<DecisionRow> lockOpenOf(Collection<UUID> conversationIds) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM decision WHERE conversation_id = ANY (CAST(? AS uuid[]))"
                + " AND status = 'OPEN' ORDER BY id FOR NO KEY UPDATE")
            .param(ConversationStore.ids(conversationIds))
            .query(DecisionStore::row)
            .list();
    }

    /**
     * An open decision's approver moves: a trigger's team or author changed, or its candidates became Viewers or
     * stopped being only Viewers (ADR-0077, decision 5).
     */
    void route(UUID decisionId, @Nullable UUID principalId, @Nullable UUID groupId, boolean admins) {
        jdbc.sql("UPDATE decision SET approver_principal_id = ?, approver_group_id = ?, approver_admins = ?"
                + " WHERE id = ? AND status = 'OPEN'"
                + " RETURNING conversation_id")
            .params(principalId, groupId, admins, decisionId)
            .query(UUID.class)
            .optional()
            .ifPresent(conversationId -> signals.changed(conversationId, ConversationSignals.Part.DECISIONS));
    }

    /** The place's answer: the commenter's name at the system and the message that answered (§5.8). */
    void answeredBy(UUID decisionId, @Nullable String name, @Nullable UUID messageId) {
        jdbc.sql("UPDATE decision SET decided_by_name = ?, decided_by_message_id = ? WHERE id = ?")
            .params(name, messageId, decisionId)
            .update();
    }

    /** The conversation's decisions, oldest first: its page shows them. */
    List<DecisionRow> ofConversation(UUID conversationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM decision WHERE conversation_id = ? ORDER BY created_at, id")
            .param(conversationId)
            .query(DecisionStore::row)
            .list();
    }

    /**
     * Locks the conversation's row, as {@link ConversationStore#lock} does: decisions of one conversation are raised
     * and decided one at a time.
     */
    void lockConversation(UUID conversationId) {
        jdbc.sql("SELECT id FROM conversation WHERE id = ? FOR NO KEY UPDATE").param(conversationId).query(UUID.class)
            .optional();
    }

    private static DecisionRow row(ResultSet rs, int rowNum) throws SQLException {
        String options = rs.getString("options");
        return new DecisionRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getObject("run_id", UUID.class),
                rs.getObject("request_id", UUID.class), Kind.valueOf(rs.getString("kind")), rs.getString("question"),
                options != null ? JSON.readValue(options, STRINGS) : List.of(),
                DecisionSubject.of(rs.getString("subject")), rs.getString("reason"),
                rs.getObject("approver_principal_id", UUID.class), Status.valueOf(rs.getString("status")),
                enumOrNull(Outcome.class, rs.getString("outcome")), rs.getString("answer"), rs.getString("option"),
                rs.getString("note"), enumOrNull(Scope.class, rs.getString("scope")),
                enumOrNull(RepositoryAccess.class, rs.getString("access")), rs.getString("result"),
                rs.getObject("decided_by_principal_id", UUID.class), instant(rs, "decided_at"),
                rs.getString("cancel_cause"), rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("reported_run_id", UUID.class), instant(rs, "call_started_at"),
                instant(rs, "purged_at"), rs.getObject("approver_group_id", UUID.class),
                rs.getString("decided_by_name"), rs.getObject("decided_by_message_id", UUID.class),
                rs.getBoolean("approver_admins"));
    }

    private static <E extends Enum<E>> @Nullable E enumOrNull(Class<E> type, @Nullable String value) {
        return value != null ? Enum.valueOf(type, value) : null;
    }

    static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
