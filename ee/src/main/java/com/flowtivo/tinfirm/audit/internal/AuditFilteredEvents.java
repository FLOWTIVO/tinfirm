package com.flowtivo.tinfirm.audit.internal;

import static com.flowtivo.tinfirm.audit.internal.AuditEventStore.COLUMNS;
import static com.flowtivo.tinfirm.audit.internal.AuditEventStore.append;
import static com.flowtivo.tinfirm.audit.internal.AuditEventStore.chain;
import static com.flowtivo.tinfirm.audit.internal.AuditEventStore.chainParams;

import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The audit's filters in SQL (ADR-0077, decision 4, {@code AUDIT_TOOLS}): the events of {@code audit_event} that match
 * a query, on the store's columns and chains ({@link AuditEventStore}). Row-level security decides which chains the
 * caller can read.
 */
@Repository
class AuditFilteredEvents {

    private final JdbcClient jdbc;

    AuditFilteredEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Newest first: events of the chain with {@code seq < beforeSeq} (all when null) that match the query, its
     * {@code conversationKey} already resolved to {@code conversationId}.
     * <p>
     * The events about a principal ({@code principalId}) are those that name it in {@code details.principalId}, those
     * whose target it is, and for a member's principal those whose target is its member's user and those that user
     * acted in ({@code principalUser}; null for a service identity or an unknown principal). Each is a branch of its
     * own on its own index ({@code audit_event_principal_idx}, {@code audit_event_target_idx} twice,
     * {@code audit_event_actor_idx}, each ending in {@code seq}), read backwards and cut at the page's size before the
     * union, so a page reads at most four pages of rows however long the principal's history is; an {@code OR} would
     * read all of it to sort it.
     */
    List<StoredAuditEvent> newestFirst(@Nullable UUID companyId, AuditQuery query, @Nullable UUID principalUser,
            @Nullable Long beforeSeq, int limit) {
        StringBuilder where = new StringBuilder(chain(companyId));
        List<Object> params = new ArrayList<>(chainParams(companyId));
        filters(where, params, query, beforeSeq);
        if (query.principalId() == null) {
            return jdbc.sql("SELECT " + COLUMNS + " FROM audit_event WHERE " + where + " ORDER BY seq DESC LIMIT ?")
                .params(append(params, limit))
                .query(AuditEventStore::map)
                .list();
        }
        List<String> branches = new ArrayList<>();
        List<Object> all = new ArrayList<>();
        String principal = query.principalId().toString();
        branch(branches, all, where, params, "details ->> 'principalId' = ?", principal, limit);
        branch(branches, all, where, params, "target_type = 'principal' AND target_id = ?", principal, limit);
        if (principalUser != null) {
            branch(branches, all, where, params, "target_type = 'user' AND target_id = ?", principalUser.toString(),
                    limit);
            branch(branches, all, where, params, "actor_type = 'USER' AND actor_id = ?", principalUser, limit);
        }
        all.add(limit);
        return jdbc.sql("SELECT * FROM (" + String.join(" UNION ", branches) + ") about ORDER BY seq DESC LIMIT ?")
            .params(all)
            .query(AuditEventStore::map)
            .list();
    }

    private static void branch(List<String> branches, List<Object> all, CharSequence where, List<Object> params,
            String predicate, Object value, int limit) {
        branches.add("(SELECT " + COLUMNS + " FROM audit_event WHERE " + where + " AND " + predicate
                + " ORDER BY seq DESC LIMIT ?)");
        all.addAll(params);
        all.add(value);
        all.add(limit);
    }

    /** The query's filters but the principal's, each with its parameter. */
    private static void filters(StringBuilder sql, List<Object> params, AuditQuery query, @Nullable Long beforeSeq) {
        if (beforeSeq != null) {
            sql.append(" AND seq < ?");
            params.add(beforeSeq);
        }
        if (query.action() != null) {
            sql.append(query.actionPrefix() ? " AND starts_with(action, ?)" : " AND action = ?");
            params.add(query.action());
        }
        if (query.actorType() != null) {
            sql.append(" AND actor_type = ?");
            params.add(query.actorType());
        }
        if (query.actorId() != null) {
            sql.append(" AND actor_id = ?");
            params.add(query.actorId());
        }
        if (query.targetType() != null) {
            sql.append(" AND target_type = ?");
            params.add(query.targetType());
        }
        if (query.targetId() != null) {
            sql.append(" AND target_id = ?");
            params.add(query.targetId());
        }
        if (query.from() != null) {
            sql.append(" AND occurred_at >= ?");
            params.add(query.from().atOffset(ZoneOffset.UTC));
        }
        if (query.to() != null) {
            sql.append(" AND occurred_at < ?");
            params.add(query.to().atOffset(ZoneOffset.UTC));
        }
        if (query.conversationId() != null) {
            sql.append(" AND details ->> 'conversationId' = ?");
            params.add(query.conversationId().toString());
        }
    }
}
