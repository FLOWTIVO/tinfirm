package com.flowtivo.tinfirm.vault.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;
import com.flowtivo.tinfirm.vault.InstanceKeys;

/**
 * An HMAC key of the instance (CONVENTIONS §9): generated once, kept under the envelope encryption, audited as a secret
 * of the instance, used inside the vault only, and never taken for a key of another algorithm.
 */
class InstanceHmacKeyIntegrationTest extends IntegrationTest {

    @Autowired
    private InstanceKeys keys;

    @Test
    void anHmacKeyIsGeneratedOnceKeptEncryptedAndAuditedAndIsNoRsaKey() throws SQLException {
        String name = "test-hmac-" + UUID.randomUUID().toString().substring(0, 8);
        byte[] data = "a state".getBytes(StandardCharsets.UTF_8);

        byte[] first = keys.hmacSha256(name, data);
        assertThat(first).hasSize(32);
        assertThat(keys.hmacSha256(name, data)).isEqualTo(first);
        assertThat(keys.hmacSha256(name, "another state".getBytes(StandardCharsets.UTF_8))).isNotEqualTo(first);
        assertThat(keys.hmacSha256(name + "-2", data)).isNotEqualTo(first);
        assertThatThrownBy(() -> keys.rsaKey(name)).isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("not an RSA key");
        assertThatThrownBy(() -> keys.hmacSha256("Not A Name", data)).isInstanceOf(IllegalArgumentException.class);

        try (Connection app = TestDatabase.connectAsApp();
                PreparedStatement select = app.prepareStatement(
                        "SELECT algorithm, octet_length(ciphertext) FROM instance_key WHERE name = ?")) {
            select.setString(1, name);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("HMAC");
                assertThat(row.getInt(2)).as("32 bytes and the GCM tag").isEqualTo(48);
            }
        }
        try (Connection superuser = TestDatabase.connectAsSuperuser(TestDatabase.jdbcUrl());
                PreparedStatement select = superuser.prepareStatement("""
                        SELECT actor_type, details->>'algorithm', details->>'bits', company_id FROM audit_event
                         WHERE action = 'secret.created' AND target_type = 'instance_key'
                           AND details->>'name' = ?""")) {
            select.setString(1, name);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo("SYSTEM");
                assertThat(row.getString(2)).isEqualTo("HMAC");
                assertThat(row.getString(3)).isEqualTo("256");
                assertThat(row.getObject(4)).as("the instance chain").isNull();
                assertThat(row.next()).isFalse();
            }
        }
    }
}
