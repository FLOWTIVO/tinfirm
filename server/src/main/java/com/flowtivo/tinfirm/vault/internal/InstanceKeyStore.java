package com.flowtivo.tinfirm.vault.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access to {@code instance_key}: the envelope-encrypted keys of the instance (ADR-0045). No row-level security. */
@Repository
class InstanceKeyStore {

    private final JdbcClient jdbc;

    InstanceKeyStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A stored key with the coordinates of its AAD. */
    record StoredKey(UUID id, String name, String kid, String algorithm, int version, EncryptedValue value) {

        InstanceKeyAad aad() {
            return new InstanceKeyAad(name, version);
        }
    }

    Optional<StoredKey> findByName(String name) {
        return jdbc.sql("SELECT * FROM instance_key WHERE name = ?").param(name).query(InstanceKeyStore::map).optional();
    }

    /** Inserts the key unless one with this name exists (another replica was first); true when this one was stored. */
    boolean insertIfAbsent(StoredKey key, Instant createdAt) {
        return jdbc.sql("""
                INSERT INTO instance_key (id, name, kid, algorithm, version, key_id, wrapped_dek, dek_nonce,
                                          value_nonce, ciphertext, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (name) DO NOTHING""")
            .params(key.id(), key.name(), key.kid(), key.algorithm(), key.version(), key.value().keyId(),
                    key.value().wrappedDek(), key.value().dekNonce(), key.value().valueNonce(),
                    key.value().ciphertext(), createdAt.atOffset(ZoneOffset.UTC))
            .update() == 1;
    }

    /** Locks up to {@code limit} keys whose data key is not wrapped under {@code activeKeyId}. */
    List<StoredKey> lockNotWrappedUnder(String activeKeyId, int limit) {
        return jdbc.sql("SELECT * FROM instance_key WHERE key_id <> ? ORDER BY name LIMIT ? FOR UPDATE")
            .params(activeKeyId, limit)
            .query(InstanceKeyStore::map)
            .list();
    }

    List<String> keyIdsOtherThan(String activeKeyId) {
        return jdbc.sql("SELECT DISTINCT key_id FROM instance_key WHERE key_id <> ? ORDER BY key_id")
            .param(activeKeyId)
            .query(String.class)
            .list();
    }

    void updateWrapping(StoredKey key, EncryptedValue rewrapped) {
        jdbc.sql("UPDATE instance_key SET key_id = ?, wrapped_dek = ?, dek_nonce = ? WHERE id = ?")
            .params(rewrapped.keyId(), rewrapped.wrappedDek(), rewrapped.dekNonce(), key.id())
            .update();
    }

    private static StoredKey map(ResultSet rs, int row) throws SQLException {
        EncryptedValue value = new EncryptedValue(rs.getString("key_id"), rs.getBytes("wrapped_dek"),
                rs.getBytes("dek_nonce"), rs.getBytes("value_nonce"), rs.getBytes("ciphertext"));
        return new StoredKey(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("kid"),
                rs.getString("algorithm"), rs.getInt("version"), value);
    }
}
