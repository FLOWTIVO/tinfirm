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

/** SQL access to {@code secret_version}: the encrypted values. */
@Repository
class SecretVersionStore {

    private final JdbcClient jdbc;

    SecretVersionStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A stored version with its row coordinates (needed for the AAD). */
    record StoredVersion(UUID secretId, int version, UUID companyId, EncryptedValue value) {

        SecretAad aad() {
            return new SecretAad(companyId, secretId, version);
        }
    }

    void insert(UUID secretId, int version, UUID companyId, EncryptedValue value, Instant createdAt) {
        jdbc.sql("""
                INSERT INTO secret_version (secret_id, version, company_id, key_id, wrapped_dek, dek_nonce,
                                            value_nonce, ciphertext, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(secretId, version, companyId, value.keyId(), value.wrappedDek(), value.dekNonce(),
                    value.valueNonce(), value.ciphertext(), createdAt.atOffset(ZoneOffset.UTC))
            .update();
    }

    Optional<StoredVersion> find(UUID secretId, int version) {
        return jdbc.sql("SELECT * FROM secret_version WHERE secret_id = ? AND version = ?")
            .params(secretId, version)
            .query(SecretVersionStore::map)
            .optional();
    }

    /** The current version of every secret of the company, with the secret's name. */
    List<NamedVersion> currentVersions(UUID companyId) {
        return jdbc.sql("""
                SELECT s.name, v.*
                  FROM secret s
                  JOIN secret_version v ON v.secret_id = s.id AND v.version = s.current_version
                 WHERE s.company_id = ?
                 ORDER BY s.name""")
            .param(companyId)
            .query((rs, row) -> new NamedVersion(rs.getString("name"), map(rs, row)))
            .list();
    }

    record NamedVersion(String name, StoredVersion version) {
    }

    /** Locks up to {@code limit} versions whose data key is not wrapped under {@code activeKeyId}. */
    List<StoredVersion> lockNotWrappedUnder(String activeKeyId, int limit) {
        return jdbc.sql("""
                SELECT * FROM secret_version
                 WHERE key_id <> ?
                 ORDER BY secret_id, version
                 LIMIT ?
                   FOR UPDATE""")
            .params(activeKeyId, limit)
            .query(SecretVersionStore::map)
            .list();
    }

    List<String> keyIdsOtherThan(String activeKeyId) {
        return jdbc.sql("SELECT DISTINCT key_id FROM secret_version WHERE key_id <> ? ORDER BY key_id")
            .param(activeKeyId)
            .query(String.class)
            .list();
    }

    void updateWrapping(StoredVersion version, EncryptedValue rewrapped) {
        jdbc.sql("""
                UPDATE secret_version SET key_id = ?, wrapped_dek = ?, dek_nonce = ?
                 WHERE secret_id = ? AND version = ?""")
            .params(rewrapped.keyId(), rewrapped.wrappedDek(), rewrapped.dekNonce(), version.secretId(),
                    version.version())
            .update();
    }

    void deleteAll(UUID secretId) {
        jdbc.sql("DELETE FROM secret_version WHERE secret_id = ?").param(secretId).update();
    }

    private static StoredVersion map(ResultSet rs, int row) throws SQLException {
        EncryptedValue value = new EncryptedValue(rs.getString("key_id"), rs.getBytes("wrapped_dek"),
                rs.getBytes("dek_nonce"), rs.getBytes("value_nonce"), rs.getBytes("ciphertext"));
        return new StoredVersion(rs.getObject("secret_id", UUID.class), rs.getInt("version"),
                rs.getObject("company_id", UUID.class), value);
    }
}
