package com.flowtivo.tinfirm.run.internal;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The SQL on {@code session_bundle} (V066) that only keeping session bundles runs (ADR-0097, ADR-0100), under
 * row-level security: a bundle found for a run, and one stored in place of the conversation agent's last. The deletes
 * that every edition runs are the core's ({@link SessionBundleStore}).
 */
@Repository
class SessionBundleRows {

    private static final String COLUMNS = "company_id, conversation_id, agent_id, runtime, session_id, object_name, "
            + "size_bytes, sha256, key_id, wrapped_key, key_nonce, run_id, created_at";

    private final JdbcClient jdbc;

    private final SessionBundleStore store;

    SessionBundleRows(JdbcClient jdbc, SessionBundleStore store) {
        this.jdbc = jdbc;
        this.store = store;
    }

    /** A bundle's row: what finds its object, opens it and checks it. */
    record BundleRow(UUID companyId, UUID conversationId, UUID agentId, String runtime, String sessionId,
            UUID objectName, int sizeBytes, String sha256, String keyId, byte[] wrappedKey, byte[] keyNonce, UUID runId,
            Instant createdAt) {
    }

    Optional<BundleRow> find(UUID conversationId, UUID agentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM session_bundle WHERE conversation_id = ? AND agent_id = ?")
            .params(conversationId, agentId)
            .query((rs, n) -> new BundleRow(rs.getObject("company_id", UUID.class),
                    rs.getObject("conversation_id", UUID.class), rs.getObject("agent_id", UUID.class),
                    rs.getString("runtime"), rs.getString("session_id"), rs.getObject("object_name", UUID.class),
                    rs.getInt("size_bytes"), rs.getString("sha256"), rs.getString("key_id"),
                    rs.getBytes("wrapped_key"), rs.getBytes("key_nonce"), rs.getObject("run_id", UUID.class),
                    rs.getTimestamp("created_at").toInstant()))
            .optional();
    }

    /** Puts the row in place of the conversation agent's bundle; answers the object of the one it replaced. */
    Optional<UUID> replace(BundleRow row) {
        Optional<UUID> replaced = store.delete(row.conversationId(), row.agentId());
        jdbc.sql("INSERT INTO session_bundle (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .params(row.companyId(), row.conversationId(), row.agentId(), row.runtime(), row.sessionId(),
                    row.objectName(), row.sizeBytes(), row.sha256(), row.keyId(), row.wrappedKey(), row.keyNonce(),
                    row.runId(), java.sql.Timestamp.from(row.createdAt()))
            .update();
        return replaced;
    }
}
