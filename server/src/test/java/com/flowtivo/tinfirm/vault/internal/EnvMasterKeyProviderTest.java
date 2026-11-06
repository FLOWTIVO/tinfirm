package com.flowtivo.tinfirm.vault.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.util.Base64;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import com.flowtivo.tinfirm.shared.config.PublishedDevSecretsTest;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.vault.MasterKeyProvider;

class EnvMasterKeyProviderTest {

    private static final String KEY = EnvelopeCipherTest.randomKey();

    @Test
    void parsesKeysAndTheActiveKey() {
        EnvMasterKeyProvider provider = new EnvMasterKeyProvider(" old:" + KEY + " , new:" + KEY + " ", "new");

        assertThat(provider.activeKeyId()).isEqualTo("new");
        assertThat(provider.keyIds()).containsExactlyInAnyOrder("old", "new");
        assertThat(provider.key("old").getAlgorithm()).isEqualTo("AES");
        assertThat(provider.toString()).doesNotContain(KEY);
    }

    @Test
    void refusesThePublishedDevelopmentKeyAsTheActiveKeyOffLocalhost() {
        // L17: the dev1 key of deploy/compose/.env.example, read from the file.
        String dev = "dev1:" + PublishedDevSecretsTest.publishedVaultKey();
        URI production = URI.create("https://tinfirm.example.com");

        assertThatThrownBy(() -> new EnvMasterKeyProvider(dev, "dev1", production))
            .isInstanceOf(VaultConfigurationException.class)
            .hasMessageContaining("development key")
            .hasMessageNotContaining(PublishedDevSecretsTest.publishedVaultKey());
        // On localhost it is the development stack's key.
        assertThat(new EnvMasterKeyProvider(dev, "dev1", URI.create("http://localhost:8080")).activeKeyId())
            .isEqualTo("dev1");
        // Retired beside a key of one's own, it is still read, so that the vault can be rewrapped off it.
        assertThat(new EnvMasterKeyProvider("prod1:" + KEY + "," + dev, "prod1", production).keyIds())
            .containsExactlyInAnyOrder("prod1", "dev1");
    }

    @Test
    void rejectsMissingOrInvalidConfigurationWithoutRevealingKeyMaterial() {
        String shortKey = Base64.getEncoder().encodeToString(new byte[16]);

        assertInvalid(null, "k1", "TINFIRM_VAULT_KEYS is not set");
        assertInvalid(" ", "k1", "TINFIRM_VAULT_KEYS is not set");
        assertInvalid("k1:" + KEY, null, "TINFIRM_VAULT_ACTIVE_KEY is not set");
        assertInvalid("k1:" + KEY, "k2", "'k2' is not one of the keys");
        assertInvalid("k1" + KEY, "k1", "malformed");
        assertInvalid("k1:" + shortKey, "k1", "must be 32 bytes");
        assertInvalid("k1:not*base64", "k1", "not valid base64");
        assertInvalid("k1:" + KEY + ",k1:" + KEY, "k1", "appears twice");
        assertInvalid("bad id:" + KEY, "bad id", "invalid key id");
    }

    @Test
    void theServerRefusesToStartWithoutValidKeys() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(VaultConfiguration.class)
            .withBean(TinfirmProperties.class,
                    () -> new TinfirmProperties(URI.create("https://tinfirm.example.com"), Set.of("api")));

        runner.run(context -> assertThat(context).hasFailed()
            .getFailure()
            .rootCause()
            .hasMessageContaining("TINFIRM_VAULT_KEYS is not set"));
        runner.withPropertyValues("tinfirm.vault.keys=k1:" + KEY)
            .run(context -> assertThat(context).hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("TINFIRM_VAULT_ACTIVE_KEY is not set"));
        runner.withPropertyValues("tinfirm.vault.keys=k1:" + KEY, "tinfirm.vault.active-key=k1")
            .run(context -> assertThat(context).hasSingleBean(MasterKeyProvider.class));
        // Off localhost, the published development key does not start the server (L17).
        runner.withPropertyValues("tinfirm.vault.keys=dev1:" + PublishedDevSecretsTest.publishedVaultKey(),
                "tinfirm.vault.active-key=dev1")
            .run(context -> assertThat(context).hasFailed()
                .getFailure()
                .rootCause()
                .hasMessageContaining("development key"));
    }

    private static void assertInvalid(String keys, String active, String message) {
        assertThatThrownBy(() -> new EnvMasterKeyProvider(keys, active)).isInstanceOf(VaultConfigurationException.class)
            .hasMessageContaining(message)
            .hasMessageNotContaining(KEY);
    }
}
