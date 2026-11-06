package com.flowtivo.tinfirm.audit.internal;

import java.time.Instant;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The filters of {@code GET /audit-events} (the package 6 contract, §5.8; CONVENTIONS §15.11), combined:
 * {@code action} exact, or a prefix when it ends in {@code .}; {@code from} included, {@code to} not;
 * {@code conversationId} matches that key of the details, and {@code conversationKey} the id of the company's
 * conversation with that key; {@code principalId} the events about the principal: named in the details' key, its
 * target, or acted by its member's user ({@link AuditEventStore#newestFirst}).
 */
record AuditQuery(@Nullable String action, @Nullable String actorType, @Nullable UUID actorId,
        @Nullable String targetType, @Nullable String targetId, @Nullable Instant from, @Nullable Instant to,
        @Nullable UUID conversationId, @Nullable String conversationKey, @Nullable UUID principalId) {

    static final AuditQuery ALL = new AuditQuery(null, null, null, null, null, null, null, null, null, null);

    boolean actionPrefix() {
        return action != null && action.endsWith(".");
    }

    /** The query from {@code since} on, included. */
    AuditQuery from(Instant since) {
        return new AuditQuery(action, actorType, actorId, targetType, targetId, since, to, conversationId,
                conversationKey, principalId);
    }

    /** The query with the conversation its key named, by id. */
    AuditQuery withConversation(UUID id) {
        return new AuditQuery(action, actorType, actorId, targetType, targetId, from, to, id, null, principalId);
    }
}
