package com.flowtivo.tinfirm.run.internal;

import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.vault.DataKeys;
import com.flowtivo.tinfirm.vault.StoredDataKeys;

/**
 * The session bundles' data keys for the vault's rewrap (ADR-0101), in every edition: a bundle that a paid licence
 * stored is rewrapped as it is deleted, without the licence. Each bundle's key is wrapped under the vault's master key
 * for its object ({@link #context}); a rewrap changes the three columns of its wrapping only (V070), never the bundle.
 */
@Repository
class SessionBundleKeys implements StoredDataKeys {

    private final JdbcClient jdbc;

    private final DataKeys dataKeys;

    SessionBundleKeys(JdbcClient jdbc, DataKeys dataKeys) {
        this.jdbc = jdbc;
        this.dataKeys = dataKeys;
    }

    /** The AAD of a bundle's data key: it opens only that bundle's object. */
    static String context(UUID companyId, UUID conversationId, UUID agentId, UUID objectName) {
        return "session-bundle|" + companyId + "|" + conversationId + "|" + agentId + "|" + objectName;
    }

    @Override
    public List<String> keyIdsOtherThan(String activeKeyId) {
        return jdbc.sql("SELECT DISTINCT key_id FROM session_bundle WHERE key_id <> ? ORDER BY key_id")
            .param(activeKeyId)
            .query(String.class)
            .list();
    }

    @Override
    public int rewrapBatch(String activeKeyId, int limit) {
        record Row(UUID companyId, UUID conversationId, UUID agentId, UUID objectName, DataKeys.WrappedKey key) {
        }
        List<Row> rows = jdbc.sql("""
                SELECT company_id, conversation_id, agent_id, object_name, key_id, wrapped_key, key_nonce
                  FROM session_bundle
                 WHERE key_id <> ?
                 ORDER BY company_id, conversation_id, agent_id
                 LIMIT ?
                   FOR UPDATE SKIP LOCKED""")
            .params(activeKeyId, limit)
            .query((rs, n) -> new Row(rs.getObject("company_id", UUID.class),
                    rs.getObject("conversation_id", UUID.class), rs.getObject("agent_id", UUID.class),
                    rs.getObject("object_name", UUID.class), new DataKeys.WrappedKey(rs.getString("key_id"),
                            rs.getBytes("wrapped_key"), rs.getBytes("key_nonce"))))
            .list();
        for (Row row : rows) {
            DataKeys.WrappedKey again = dataKeys.rewrap(context(row.companyId(), row.conversationId(), row.agentId(),
                    row.objectName()), row.key());
            jdbc.sql("""
                    UPDATE session_bundle SET key_id = ?, wrapped_key = ?, key_nonce = ?
                     WHERE company_id = ? AND conversation_id = ? AND agent_id = ? AND object_name = ?""")
                .params(again.keyId(), again.wrapped(), again.nonce(), row.companyId(), row.conversationId(),
                        row.agentId(), row.objectName())
                .update();
        }
        return rows.size();
    }
}
