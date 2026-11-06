package com.flowtivo.tinfirm.vault.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class EnvelopeCipherTest {

    private static final String KEY_1 = randomKey();

    private static final String KEY_2 = randomKey();

    private final SecretAad aad = new SecretAad(UUID.randomUUID(), UUID.randomUUID(), 1);

    @Test
    void encryptsAndDecryptsWithAFreshDataKeyAndNonce() {
        EnvelopeCipher cipher = cipher("k1:" + KEY_1, "k1");
        byte[] plaintext = "top secret".getBytes(StandardCharsets.UTF_8);

        EncryptedValue first = cipher.encrypt(aad, plaintext);
        EncryptedValue second = cipher.encrypt(aad, plaintext);

        assertThat(first.keyId()).isEqualTo("k1");
        assertThat(first.valueNonce()).hasSize(12);
        assertThat(first.dekNonce()).hasSize(12);
        assertThat(first.wrappedDek()).hasSize(32 + 16);
        assertThat(first.ciphertext()).isNotEqualTo(second.ciphertext());
        assertThat(first.wrappedDek()).isNotEqualTo(second.wrappedDek());
        assertThat(new String(cipher.decrypt(aad, first), StandardCharsets.UTF_8)).isEqualTo("top secret");
        assertThat(first.toString()).doesNotContain("top secret");
    }

    @Test
    void failsWithTheWrongMasterKey() {
        EncryptedValue encrypted = cipher("k1:" + KEY_1, "k1").encrypt(aad, "value".getBytes(StandardCharsets.UTF_8));
        // Same key id, different key material.
        EnvelopeCipher wrongKey = cipher("k1:" + KEY_2, "k1");

        assertThatThrownBy(() -> wrongKey.decrypt(aad, encrypted)).isInstanceOf(VaultCryptoException.class)
            .hasMessageNotContaining("value");
    }

    @Test
    void theAadBindsTheCiphertextToItsRow() {
        EnvelopeCipher cipher = cipher("k1:" + KEY_1, "k1");
        EncryptedValue encrypted = cipher.encrypt(aad, "value".getBytes(StandardCharsets.UTF_8));

        for (SecretAad other : new SecretAad[] { new SecretAad(UUID.randomUUID(), aad.secretId(), 1),
                new SecretAad(aad.companyId(), UUID.randomUUID(), 1), new SecretAad(aad.companyId(), aad.secretId(), 2) }) {
            assertThatThrownBy(() -> cipher.decrypt(other, encrypted)).isInstanceOf(VaultCryptoException.class);
        }
    }

    @Test
    void rewrapMovesTheDataKeyToTheActiveMasterKeyAndKeepsTheValue() {
        EncryptedValue underKey1 = cipher("k1:" + KEY_1, "k1").encrypt(aad, "value".getBytes(StandardCharsets.UTF_8));
        EnvelopeCipher rotated = cipher("k1:" + KEY_1 + ",k2:" + KEY_2, "k2");

        EncryptedValue underKey2 = rotated.rewrap(aad, underKey1);

        assertThat(underKey2.keyId()).isEqualTo("k2");
        assertThat(underKey2.ciphertext()).isEqualTo(underKey1.ciphertext());
        assertThat(underKey2.wrappedDek()).isNotEqualTo(underKey1.wrappedDek());
        assertThat(new String(cipher("k2:" + KEY_2, "k2").decrypt(aad, underKey2), StandardCharsets.UTF_8))
            .isEqualTo("value");
        // Once the old key is gone, only the rewrapped value is readable.
        assertThatThrownBy(() -> cipher("k2:" + KEY_2, "k2").decrypt(aad, underKey1))
            .isInstanceOf(VaultCryptoException.class)
            .hasMessageContaining("'k1' is not configured");
    }

    private static EnvelopeCipher cipher(String keys, String active) {
        return new EnvelopeCipher(new EnvMasterKeyProvider(keys, active));
    }

    static String randomKey() {
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }
}
