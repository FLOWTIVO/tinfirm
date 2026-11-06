package com.flowtivo.tinfirm.vault.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.vault.DataKeys;

class DataKeysTest {

    private static final String KEY_1 = EnvelopeCipherTest.randomKey();

    private static final String KEY_2 = EnvelopeCipherTest.randomKey();

    private static DataKeys keys(String keys, String active) {
        return new DataKeysImpl(new EnvelopeCipher(new EnvMasterKeyProvider(keys, active)));
    }

    @Test
    void aFreshKeyIsWrappedUnderTheActiveMasterKeyAndUnwrapsForItsContextOnly() {
        DataKeys keys = keys("k1:" + KEY_1, "k1");
        try (DataKeys.DataKey first = keys.create("session-bundle|a"); DataKeys.DataKey second = keys
            .create("session-bundle|a")) {
            assertThat(first.wrapped().keyId()).isEqualTo("k1");
            assertThat(first.wrapped().wrapped()).hasSize(32 + 16);
            assertThat(first.wrapped().nonce()).hasSize(12);
            assertThat(first.secretKey().getEncoded()).isNotEqualTo(second.secretKey().getEncoded());
            try (DataKeys.DataKey again = keys.unwrap("session-bundle|a", first.wrapped())) {
                assertThat(again.secretKey().getEncoded()).isEqualTo(first.secretKey().getEncoded());
            }
            // Another object's context, or a secret's AAD, opens nothing.
            assertThatThrownBy(() -> keys.unwrap("session-bundle|b", first.wrapped()))
                .isInstanceOf(VaultCryptoException.class);
        }
    }

    @Test
    void aRotatedMasterKeyStillUnwrapsOldKeysAndARetiredOneDoesNot() {
        DataKeys.WrappedKey underKey1;
        byte[] key;
        try (DataKeys.DataKey created = keys("k1:" + KEY_1, "k1").create("c")) {
            underKey1 = created.wrapped();
            key = created.secretKey().getEncoded();
        }
        try (DataKeys.DataKey rotated = keys("k1:" + KEY_1 + ",k2:" + KEY_2, "k2").unwrap("c", underKey1)) {
            assertThat(rotated.secretKey().getEncoded()).isEqualTo(key);
        }
        assertThatThrownBy(() -> keys("k2:" + KEY_2, "k2").unwrap("c", underKey1))
            .isInstanceOf(VaultCryptoException.class)
            .hasMessageContaining("not configured");
    }
}
