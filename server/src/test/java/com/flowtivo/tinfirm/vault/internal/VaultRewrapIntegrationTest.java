package com.flowtivo.tinfirm.vault.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;
import com.flowtivo.tinfirm.vault.RevealedSecret;
import com.flowtivo.tinfirm.vault.VaultService;

import tools.jackson.databind.JsonNode;

/**
 * Master key rotation: test-k1 is the active key (the test profile's), and test-k2 stands for the key before the
 * rotation, still configured for unwrapping. So the test shares the integration tests' context.
 */
class VaultRewrapIntegrationTest extends IntegrationTest {

    @Value("${tinfirm.vault.keys}")
    String keys;

    @Autowired
    VaultService vaultService;

    @Test
    void rewrapMovesEveryDataKeyToTheActiveKeyIdempotently() throws SQLException {
        ApiClient admin = admin();
        UUID companyId = createCompany(admin, unique("Rotation"));
        String value = "rotate-me-" + UUID.randomUUID();
        UUID secretId = createSecret(admin, companyId, "ROTATED", value);
        assertThat(keyIdOf(secretId)).isEqualTo("test-k1");
        // Simulate a value written before the rotation, when test-k2 was active.
        EnvelopeCipher before = new EnvelopeCipher(new EnvMasterKeyProvider(keys, "test-k2"));
        store(secretId, before.encrypt(new SecretAad(companyId, secretId, 1), value.getBytes(StandardCharsets.UTF_8)));
        assertThat(keyIdOf(secretId)).isEqualTo("test-k2");

        ApiResponse first = admin.post("/api/admin/vault/rewrap", null);

        assertThat(first.status()).isEqualTo(200);
        assertThat(first.json().get("activeKeyId").asString()).isEqualTo("test-k1");
        assertThat(first.json().get("rewrapped").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(keyIdOf(secretId)).isEqualTo("test-k1");
        try (RevealedSecret revealed = SystemContext.call(() -> vaultService.reveal(companyId, secretId, "test:rewrap"))) {
            assertThat(revealed.asString()).isEqualTo(value);
        }

        ApiResponse second = admin.post("/api/admin/vault/rewrap", null);
        assertThat(second.json().get("rewrapped").asLong()).isZero();

        JsonNode audited = admin.get("/api/admin/audit-events?limit=1").json().get("items").get(0);
        assertThat(audited.get("action").asString()).isEqualTo("vault.rewrapped");
        assertThat(audited.get("details").get("activeKeyId").asString()).isEqualTo("test-k1");
        assertThat(audited.get("details").get("rewrapped").asLong()).isZero();
    }

    @Test
    void rewrapRefusesToRunWhenAnOldMasterKeyIsMissing() throws SQLException {
        ApiClient admin = admin();
        UUID companyId = createCompany(admin, unique("Missing"));
        UUID secretId = createSecret(admin, companyId, "ORPHANED", "value");
        setKeyId(secretId, "retired-key");
        try {
            ApiResponse failed = admin.post("/api/admin/vault/rewrap", null);

            assertThat(failed.status()).isEqualTo(500);
            assertThat(failed.json().get("detail").asString()).contains("retired-key");
        }
        finally {
            setKeyId(secretId, "test-k1");
        }
        assertThat(admin.post("/api/admin/vault/rewrap", null).status()).isEqualTo(200);
    }

    private static String keyIdOf(UUID secretId) throws SQLException {
        try (Connection owner = ownerAsSystem();
                PreparedStatement query = owner.prepareStatement("SELECT key_id FROM secret_version WHERE secret_id = ?")) {
            query.setObject(1, secretId);
            try (ResultSet row = query.executeQuery()) {
                row.next();
                return row.getString(1);
            }
        }
    }

    private static void store(UUID secretId, EncryptedValue value) throws SQLException {
        try (Connection owner = ownerAsSystem(); PreparedStatement update = owner.prepareStatement("""
                UPDATE secret_version
                   SET key_id = ?, wrapped_dek = ?, dek_nonce = ?, value_nonce = ?, ciphertext = ?
                 WHERE secret_id = ? AND version = 1""")) {
            update.setString(1, value.keyId());
            update.setBytes(2, value.wrappedDek());
            update.setBytes(3, value.dekNonce());
            update.setBytes(4, value.valueNonce());
            update.setBytes(5, value.ciphertext());
            update.setObject(6, secretId);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private static void setKeyId(UUID secretId, String keyId) throws SQLException {
        try (Connection owner = ownerAsSystem();
                PreparedStatement update = owner.prepareStatement("UPDATE secret_version SET key_id = ? WHERE secret_id = ?")) {
            update.setString(1, keyId);
            update.setObject(2, secretId);
            update.executeUpdate();
        }
    }

    /** The owner is subject to FORCE ROW LEVEL SECURITY too; the system context (app.system) lets it see all rows. */
    private static Connection ownerAsSystem() throws SQLException {
        Connection connection = TestDatabase.connectAsOwner();
        connection.createStatement().execute("SET app.system = 'true'");
        return connection;
    }
}
