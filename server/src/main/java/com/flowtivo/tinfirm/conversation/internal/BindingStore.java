package com.flowtivo.tinfirm.conversation.internal;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.conversation.BindingInfo;

/**
 * SQL access to {@code conversation_binding} (V005), under row-level security: the account a conversation uses on one
 * system (the package 3 contract, §3.9). The database keeps I6 to I8 (its trigger); this store writes what
 * {@link BindingService} decided.
 */
@Repository
class BindingStore {

    /**
     * How many calls wait on a binding: part 2's parked calls that have not run or been answered yet (a call's
     * {@code status} {@code PARKED}, {@code ALLOWED} or {@code RUNNING}); one that ran keeps its result on the binding
     * until a turn told the agent, and holds nothing up.
     */
    private static final String PARKED = "(SELECT count(*) FROM jsonb_array_elements(%s) AS call"
            + " WHERE coalesce(call ->> 'status', 'PARKED') IN ('PARKED', 'ALLOWED', 'RUNNING'))";

    private static final String COLUMNS = "id, company_id, conversation_id, system_id, state, how, account_id,"
            + " holder_principal_id, external_id, grant_id, reason, choices, "
            + PARKED.formatted("waiting_calls") + " AS parked, waiting_since, bound_at, run_id";

    private final JdbcClient jdbc;

    BindingStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Whether a binding of the conversation holds a call that waited for it, ran or was refused, and that no turn told
     * yet (part 2's {@code reportedRunId}): one settled while a run was active, whose {@code CONNECTED} turn could not
     * start then (I11).
     */
    boolean settledCallsUntold(UUID companyId, UUID conversationId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM conversation_binding b, jsonb_array_elements(b.waiting_calls) AS call
                                WHERE b.company_id = ? AND b.conversation_id = ?
                                  AND call ->> 'status' IN ('DONE', 'REFUSED') AND call ->> 'reportedRunId' IS NULL)""")
            .params(companyId, conversationId)
            .query(Boolean.class)
            .single();
    }

    /**
     * A binding and how many calls are parked on it.
     *
     * @param runId the run whose tool call or workspace first asked for it (V032); null when no run did
     */
    record BindingRow(BindingInfo info, int parkedCalls, @Nullable UUID runId) {
    }

    /**
     * Inserts the conversation's binding on the system unless it has one (I6: one per system per conversation).
     *
     * @return whether it was inserted
     */
    boolean insert(BindingInfo binding, @Nullable UUID boundBy, Instant now) {
        return insert(binding, boundBy, null, now);
    }

    /**
     * {@link #insert(BindingInfo, UUID, Instant)}, asked for by the run.
     *
     * @param runId the run whose tool call or workspace asks for it; null for none
     */
    boolean insert(BindingInfo binding, @Nullable UUID boundBy, @Nullable UUID runId, Instant now) {
        return jdbc.sql("""
                INSERT INTO conversation_binding (id, company_id, conversation_id, system_id, state, how, account_id,
                                                  holder_principal_id, external_id, grant_id, reason, choices,
                                                  waiting_since, bound_at, bound_by, created_at, updated_at, run_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CAST(? AS uuid[]), ?, ?, ?, ?, ?, ?)
                ON CONFLICT (conversation_id, system_id) DO NOTHING""")
            .params(binding.id(), binding.companyId(), binding.conversationId(), binding.systemId(),
                    binding.state().name(), binding.how() != null ? binding.how().name() : null, binding.accountId(),
                    binding.holderPrincipalId(), binding.externalId(), binding.grantId(),
                    binding.reason() != null ? binding.reason().name() : null,
                    ConversationStore.ids(binding.choices()),
                    binding.waitingSince() != null ? ConversationStore.ts(binding.waitingSince()) : null,
                    binding.boundAt() != null ? ConversationStore.ts(binding.boundAt()) : null, boundBy,
                    ConversationStore.ts(now), ConversationStore.ts(now), runId)
            .update() == 1;
    }

    /**
     * The turn's run asked for the bindings its workspace's systems made before it existed: they name it, unless a run
     * asked first.
     */
    void askedBy(UUID companyId, UUID conversationId, Collection<UUID> systemIds, UUID runId) {
        if (systemIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                UPDATE conversation_binding SET run_id = ?
                 WHERE company_id = ? AND conversation_id = ? AND system_id = ANY (CAST(? AS uuid[]))
                   AND run_id IS NULL AND how IS DISTINCT FROM 'FIXED'""")
            .params(runId, companyId, conversationId, ConversationStore.ids(systemIds))
            .update();
    }

    Optional<BindingRow> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversation_binding WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(BindingStore::row)
            .optional();
    }

    Optional<BindingRow> lock(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversation_binding WHERE company_id = ? AND id = ? FOR UPDATE")
            .params(companyId, id)
            .query(BindingStore::row)
            .optional();
    }

    Optional<BindingRow> of(UUID companyId, UUID conversationId, UUID systemId) {
        return jdbc.sql("SELECT " + COLUMNS
                + " FROM conversation_binding WHERE company_id = ? AND conversation_id = ? AND system_id = ?")
            .params(companyId, conversationId, systemId)
            .query(BindingStore::row)
            .optional();
    }

    /** The conversation's bindings, oldest first. */
    List<BindingRow> ofConversation(UUID companyId, UUID conversationId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM conversation_binding WHERE company_id = ? AND conversation_id = ?"
                + " ORDER BY created_at, id")
            .params(companyId, conversationId)
            .query(BindingStore::row)
            .list();
    }

    /**
     * The bindings of open conversations that are not gone, matching any of the conditions: on one of the systems, of
     * one of the accounts, through one of the grants, of one of the holders, of a conversation of one of the persons.
     */
    List<BindingRow> concerned(UUID companyId, Collection<UUID> systemIds, Collection<UUID> accountIds,
            Collection<UUID> grantIds, Collection<UUID> holderIds, Collection<UUID> personIds) {
        return jdbc.sql("""
                SELECT b.id, b.company_id, b.conversation_id, b.system_id, b.state, b.how, b.account_id,
                       b.holder_principal_id, b.external_id, b.grant_id, b.reason, b.choices,
                       """ + PARKED.formatted("b.waiting_calls") + """
                        AS parked, b.waiting_since, b.bound_at, b.run_id
                  FROM conversation_binding b
                  JOIN conversation c ON c.id = b.conversation_id AND c.company_id = b.company_id
                 WHERE b.company_id = ? AND b.state <> 'GONE' AND c.state = 'OPEN'
                   AND (b.system_id = ANY (CAST(? AS uuid[])) OR b.account_id = ANY (CAST(? AS uuid[]))
                        OR b.grant_id = ANY (CAST(? AS uuid[])) OR b.holder_principal_id = ANY (CAST(? AS uuid[]))
                        OR c.person_principal_id = ANY (CAST(? AS uuid[])))
                 ORDER BY b.created_at, b.id""")
            .params(companyId, ConversationStore.ids(systemIds), ConversationStore.ids(accountIds),
                    ConversationStore.ids(grantIds), ConversationStore.ids(holderIds),
                    ConversationStore.ids(personIds))
            .query(BindingStore::row)
            .list();
    }

    /** The binding is bound (first, or again after it waited), with its account. */
    void bound(UUID companyId, UUID id, BindingInfo.How how, UUID accountId, @Nullable UUID holderPrincipalId,
            @Nullable String externalId, @Nullable UUID grantId, @Nullable UUID boundBy, Instant now) {
        jdbc.sql("""
                UPDATE conversation_binding
                   SET state = 'BOUND', how = ?, account_id = ?, holder_principal_id = ?, external_id = ?, grant_id = ?,
                       reason = NULL, choices = '{}', waiting_since = NULL,
                       bound_at = coalesce(bound_at, ?), bound_by = coalesce(?, bound_by), updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(how.name(), accountId, holderPrincipalId, externalId, grantId, ConversationStore.ts(now), boundBy,
                    ConversationStore.ts(now), companyId, id)
            .update();
    }

    /**
     * The binding holds without an account (ADR-0071): first, or again after it waited. It names no account, holder,
     * identity or grant; the database lets it only on a system that needs no account
     * ({@code conversation_binding_open_ck}).
     */
    void open(UUID companyId, UUID id, @Nullable UUID boundBy, Instant now) {
        jdbc.sql("""
                UPDATE conversation_binding
                   SET state = 'BOUND', how = 'OPEN', reason = NULL, choices = '{}', waiting_since = NULL,
                       bound_at = coalesce(bound_at, ?), bound_by = coalesce(?, bound_by), updated_at = ?
                 WHERE company_id = ? AND id = ? AND account_id IS NULL""")
            .params(ConversationStore.ts(now), boundBy, ConversationStore.ts(now), companyId, id)
            .update();
    }

    /** The holder's account was replaced by one with the same identity (a reconnect): the binding names it (I6). */
    void replaceAccount(UUID companyId, UUID id, UUID accountId, Instant now) {
        jdbc.sql("UPDATE conversation_binding SET account_id = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(accountId, ConversationStore.ts(now), companyId, id)
            .update();
    }

    /** The binding waits for the reason; {@code waiting_since} moves only when it starts to wait. */
    void waiting(UUID companyId, UUID id, BindingInfo.Reason reason, List<UUID> choices, Instant now) {
        jdbc.sql("""
                UPDATE conversation_binding
                   SET state = 'WAITING', reason = ?, choices = CAST(? AS uuid[]),
                       waiting_since = CASE WHEN state = 'WAITING' THEN waiting_since ELSE ? END, updated_at = ?
                 WHERE company_id = ? AND id = ? AND state <> 'GONE'""")
            .params(reason.name(), ConversationStore.ids(choices), ConversationStore.ts(now),
                    ConversationStore.ts(now), companyId, id)
            .update();
    }

    void gone(UUID companyId, UUID id, BindingInfo.Reason reason, Instant now) {
        jdbc.sql("""
                UPDATE conversation_binding
                   SET state = 'GONE', reason = ?, choices = '{}', waiting_since = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ? AND state <> 'GONE'""")
            .params(reason.name(), ConversationStore.ts(now), companyId, id)
            .update();
    }

    /** The calls parked on the conversation's bindings are dropped: the conversation ended or was cancelled. */
    void dropParkedCalls(UUID companyId, UUID conversationId, Instant now) {
        jdbc.sql("""
                UPDATE conversation_binding SET waiting_calls = '[]'::jsonb, updated_at = ?
                 WHERE company_id = ? AND conversation_id = ? AND waiting_calls <> '[]'::jsonb""")
            .params(ConversationStore.ts(now), companyId, conversationId)
            .update();
    }

    private static BindingRow row(ResultSet rs, int rowNum) throws SQLException {
        String how = rs.getString("how");
        String reason = rs.getString("reason");
        BindingInfo info = new BindingInfo(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("conversation_id", UUID.class), rs.getObject("system_id", UUID.class),
                BindingInfo.State.valueOf(rs.getString("state")), how != null ? BindingInfo.How.valueOf(how) : null,
                rs.getObject("account_id", UUID.class), rs.getObject("holder_principal_id", UUID.class),
                rs.getString("external_id"), rs.getObject("grant_id", UUID.class),
                reason != null ? BindingInfo.Reason.valueOf(reason) : null, uuids(rs.getArray("choices")),
                ConversationStore.nullableInstant(rs, "waiting_since"),
                ConversationStore.nullableInstant(rs, "bound_at"));
        return new BindingRow(info, rs.getInt("parked"), rs.getObject("run_id", UUID.class));
    }

    private static List<UUID> uuids(@Nullable Array array) throws SQLException {
        List<UUID> ids = new ArrayList<>();
        if (array != null) {
            for (Object id : (Object[]) array.getArray()) {
                ids.add((UUID) id);
            }
        }
        return ids;
    }
}
