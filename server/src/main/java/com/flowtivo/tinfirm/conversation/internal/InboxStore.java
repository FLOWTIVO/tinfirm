package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The inbox's reads (the package 3 contract, §5.10) over the conversation module's tables, longest waiting first. Row
 * level security limits them to the company of the tenant context; the caller limits them to what the caller acts on.
 */
@Repository
class InboxStore {

    /** The waiting reasons the person acts on themselves. */
    private static final String PERSON_ACTS = "('NONE', 'RECONNECT_YOURS', 'CHOOSE', 'PICK_GRANT')";

    /** The waiting reasons an admin acts on: a service identity's account, or its suspended holder. */
    private static final String ADMIN_ACTS = "('RECONNECT_COMPANY', 'SUSPENDED')";

    private final JdbcClient jdbc;

    InboxStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A waiting binding of a person's open conversation. */
    record WaitingBinding(UUID id, UUID conversationId, UUID systemId, String reason, List<UUID> choices,
            Instant since) {
    }

    /** A person's open conversation whose latest turn failed, or that waits on a runner or a budget. */
    record ConversationRow(UUID id, String key, String title, UUID agentId, @Nullable String turnStatus,
            @Nullable String waitingReason, Instant since, @Nullable UUID lastRunId, @Nullable UUID activeRunId,
            @Nullable String failureClass, @Nullable String failureCause) {
    }

    /** An account, or a system's granted accounts, that keep conversations waiting for an admin. */
    record WaitingAccount(UUID systemId, @Nullable UUID accountId, @Nullable UUID holderPrincipalId, String reason,
            int conversations, Instant since) {
    }

    List<WaitingBinding> bindings(UUID personPrincipalId, int limit) {
        return jdbc.sql("""
                SELECT b.id, b.conversation_id, b.system_id, b.reason, b.choices, b.waiting_since
                  FROM conversation_binding b JOIN conversation c ON c.id = b.conversation_id
                 WHERE b.state = 'WAITING' AND b.reason IN""" + " " + PERSON_ACTS + " " + """
                   AND c.person_principal_id = ? AND c.state = 'OPEN'
                 ORDER BY b.waiting_since, b.id LIMIT ?""")
            .params(personPrincipalId, limit)
            .query((rs, row) -> new WaitingBinding(rs.getObject("id", UUID.class),
                    rs.getObject("conversation_id", UUID.class), rs.getObject("system_id", UUID.class),
                    rs.getString("reason"), uuids(rs.getArray("choices")),
                    DecisionStore.instant(rs, "waiting_since")))
            .list();
    }

    int countBindings(UUID personPrincipalId) {
        return jdbc.sql("""
                SELECT count(*) FROM conversation_binding b JOIN conversation c ON c.id = b.conversation_id
                 WHERE b.state = 'WAITING' AND b.reason IN""" + " " + PERSON_ACTS + " " + """
                   AND c.person_principal_id = ? AND c.state = 'OPEN'""")
            .param(personPrincipalId)
            .query(Integer.class)
            .single();
    }

    /**
     * The person's open conversations whose latest turn failed, and those of the triggers they author or whose team
     * they are in (the package 4 contract, §5.13).
     */
    List<ConversationRow> failed(UUID personPrincipalId, Collection<UUID> triggerIds, int limit) {
        return conversations("turn_status = 'FAILED'", personPrincipalId, triggerIds, limit);
    }

    int countFailed(UUID personPrincipalId, Collection<UUID> triggerIds) {
        return count("turn_status = 'FAILED'", personPrincipalId, triggerIds);
    }

    /** The person's open conversations waiting on a runner, or on their budget. */
    List<ConversationRow> waiting(UUID personPrincipalId, int limit) {
        return conversations("waiting_on = 'RUNNER'", personPrincipalId, List.of(), limit);
    }

    int countWaiting(UUID personPrincipalId) {
        return count("waiting_on = 'RUNNER'", personPrincipalId, List.of());
    }

    /**
     * The accounts that keep open conversations waiting for an admin, each with how many: by the account a binding
     * names, or by the system for a binding that never bound (a granted account to reconnect it found broken).
     */
    List<WaitingAccount> accounts(int limit) {
        return jdbc.sql("""
                SELECT b.system_id, b.account_id, b.holder_principal_id, b.reason,
                       count(DISTINCT b.conversation_id) AS conversations, min(b.waiting_since) AS since
                  FROM conversation_binding b JOIN conversation c ON c.id = b.conversation_id
                 WHERE b.state = 'WAITING' AND b.reason IN""" + " " + ADMIN_ACTS + " " + """
                   AND c.state = 'OPEN'
                 GROUP BY b.system_id, b.account_id, b.holder_principal_id, b.reason
                 ORDER BY since, b.system_id LIMIT ?""")
            .param(limit)
            .query((rs, row) -> new WaitingAccount(rs.getObject("system_id", UUID.class),
                    rs.getObject("account_id", UUID.class), rs.getObject("holder_principal_id", UUID.class),
                    rs.getString("reason"), rs.getInt("conversations"), DecisionStore.instant(rs, "since")))
            .list();
    }

    /** A conversation, as the inbox names it. */
    java.util.Optional<ConversationRow> conversation(UUID conversationId) {
        return jdbc.sql("""
                SELECT id, key, coalesce(title, key) AS title, agent_id, turn_status, waiting_reason,
                       coalesce(waiting_since, last_turn_at, updated_at) AS since, last_run_id, active_run_id,
                       failure_class, failure_cause
                  FROM conversation WHERE id = ?""")
            .param(conversationId)
            .query(InboxStore::conversationRow)
            .optional();
    }

    private static ConversationRow conversationRow(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new ConversationRow(rs.getObject("id", UUID.class), rs.getString("key"), rs.getString("title"),
                rs.getObject("agent_id", UUID.class), rs.getString("turn_status"), rs.getString("waiting_reason"),
                DecisionStore.instant(rs, "since"), rs.getObject("last_run_id", UUID.class),
                rs.getObject("active_run_id", UUID.class), rs.getString("failure_class"),
                rs.getString("failure_cause"));
    }

    private List<ConversationRow> conversations(String condition, UUID personPrincipalId,
            Collection<UUID> triggerIds, int limit) {
        return jdbc.sql("""
                SELECT id, key, coalesce(title, key) AS title, agent_id, turn_status, waiting_reason,
                       coalesce(waiting_since, last_turn_at, updated_at) AS since, last_run_id, active_run_id,
                       failure_class, failure_cause
                  FROM conversation
                 WHERE (person_principal_id = ? OR trigger_id = ANY (CAST(? AS uuid[]))) AND state = 'OPEN'"""
                + " AND " + condition + " " + """
                 ORDER BY since, id LIMIT ?""")
            .params(personPrincipalId, ConversationStore.ids(triggerIds), limit)
            .query((rs, row) -> new ConversationRow(rs.getObject("id", UUID.class), rs.getString("key"),
                    rs.getString("title"), rs.getObject("agent_id", UUID.class), rs.getString("turn_status"),
                    rs.getString("waiting_reason"), DecisionStore.instant(rs, "since"),
                    rs.getObject("last_run_id", UUID.class), rs.getObject("active_run_id", UUID.class),
                    rs.getString("failure_class"), rs.getString("failure_cause")))
            .list();
    }

    private int count(String condition, UUID personPrincipalId, Collection<UUID> triggerIds) {
        return jdbc.sql("SELECT count(*) FROM conversation WHERE (person_principal_id = ?"
                + " OR trigger_id = ANY (CAST(? AS uuid[]))) AND state = 'OPEN' AND " + condition)
            .params(personPrincipalId, ConversationStore.ids(triggerIds))
            .query(Integer.class)
            .single();
    }

    private static List<UUID> uuids(java.sql.@Nullable Array array) throws java.sql.SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.stream((Object[]) array.getArray()).map(UUID.class::cast).toList();
    }
}
