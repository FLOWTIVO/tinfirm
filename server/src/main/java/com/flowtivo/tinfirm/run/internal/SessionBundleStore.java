package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL access to {@code session_bundle} (V066, V068) that every edition runs, under row-level security, and the
 * sweeper's two cross-company lookups: the deletes of a conversation's bundles, of an older one and of a due one
 * (ADR-0097). Finding
 * and storing a bundle is the paid feature's ({@code SessionBundleRows}, under {@code ee/}).
 */
@Repository
class SessionBundleStore {

    private final JdbcClient jdbc;

    SessionBundleStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A bundle that is due, as the sweeper's lookup answers it: the pair, its company and the object it named then. */
    record Due(UUID companyId, UUID conversationId, UUID agentId, UUID objectName) {
    }

    /** Deletes the conversation agent's bundle; answers its object. */
    Optional<UUID> delete(UUID conversationId, UUID agentId) {
        return jdbc.sql("DELETE FROM session_bundle WHERE conversation_id = ? AND agent_id = ? RETURNING object_name")
            .params(conversationId, agentId)
            .query(UUID.class)
            .optional();
    }

    /** Deletes the bundles of the conversation, of every agent; answers their objects. */
    List<UUID> deleteOf(UUID conversationId) {
        return jdbc.sql("DELETE FROM session_bundle WHERE conversation_id = ? RETURNING object_name")
            .params(conversationId)
            .query(UUID.class)
            .list();
    }

    /** Deletes the conversation agent's bundle unless that run uploaded it; answers its object. */
    Optional<UUID> deleteUnlessOf(UUID conversationId, UUID agentId, UUID runId) {
        return jdbc.sql("DELETE FROM session_bundle WHERE conversation_id = ? AND agent_id = ? AND run_id <> ?"
                + " RETURNING object_name")
            .params(conversationId, agentId, runId)
            .query(UUID.class)
            .optional();
    }

    /**
     * The sweeper's delete of a due bundle, in the company's context with the conversation's row locked: the row goes
     * only while it still names the object the lookup found ({@link #due}: a run that replaced the bundle since named
     * a new one) and its conversation is still due by the lookup's own rule (ended, or no turn since the cutoff: a
     * turn started since set {@code last_turn_at}). Answers the object; empty when the conversation resumed meanwhile,
     * whose bundle stays.
     */
    Optional<UUID> deleteDue(UUID conversationId, UUID agentId, UUID objectName, Instant before) {
        return jdbc.sql("DELETE FROM session_bundle b WHERE b.conversation_id = ? AND b.agent_id = ?"
                + " AND b.object_name = ? AND EXISTS (SELECT 1 FROM conversation c WHERE c.id = b.conversation_id"
                + " AND c.company_id = b.company_id AND (c.state = 'ENDED'"
                + " OR coalesce(c.last_turn_at, b.created_at) < ?)) RETURNING b.object_name")
            .params(conversationId, agentId, objectName, java.sql.Timestamp.from(before))
            .query(UUID.class)
            .optional();
    }

    /** The bundles that are due, of every company (unscoped: a SECURITY DEFINER lookup), each with its object. */
    List<Due> due(Instant before, int limit) {
        return jdbc.sql("SELECT company_id, conversation_id, agent_id, object_name FROM session_bundle_due(?, ?)")
            .params(java.sql.Timestamp.from(before), limit)
            .query((rs, n) -> new Due(rs.getObject("company_id", UUID.class),
                    rs.getObject("conversation_id", UUID.class), rs.getObject("agent_id", UUID.class),
                    rs.getObject("object_name", UUID.class)))
            .list();
    }

    /** Which of these objects a bundle names, of every company (unscoped: a SECURITY DEFINER lookup). */
    Set<UUID> known(Collection<UUID> names) {
        if (names.isEmpty()) {
            return Set.of();
        }
        String array = names.stream().map(UUID::toString).collect(Collectors.joining(",", "{", "}"));
        return new HashSet<>(jdbc.sql("SELECT object_name FROM session_bundle_objects_known(?::uuid[])")
            .param(array)
            .query(UUID.class)
            .list());
    }
}
