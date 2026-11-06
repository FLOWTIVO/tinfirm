package com.flowtivo.tinfirm.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * L17 (threat model 4.3, Q9): the server knows the development secrets of {@code deploy/compose/.env.example} by their
 * SHA-256, read here from the file itself, so that the constants follow it; and refuses them off localhost.
 */
public class PublishedDevSecretsTest {

    /** The repository's {@code deploy/compose/.env.example}, from the server module's directory. */
    private static final Path ENV_EXAMPLE = Path.of("..", "deploy", "compose", ".env.example");

    /** The value of a variable in {@code .env.example}. */
    public static String published(String variable) {
        try {
            List<String> lines = Files.readAllLines(ENV_EXAMPLE);
            return lines.stream()
                .filter(line -> line.startsWith(variable + "="))
                .map(line -> line.substring(variable.length() + 1).trim())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(variable + " is not in " + ENV_EXAMPLE));
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The published master key: the value of {@code dev1} in {@code TINFIRM_VAULT_KEYS}. */
    public static String publishedVaultKey() {
        String keys = published("TINFIRM_VAULT_KEYS");
        return keys.substring(keys.indexOf(':') + 1);
    }

    @Test
    void knowsThePublishedSecretsOfEnvExample() {
        assertThat(PublishedDevSecrets.isPublishedVaultKey(Base64.getDecoder().decode(publishedVaultKey()))).isTrue();
        assertThat(PublishedDevSecrets.isPublishedLiteLlmMasterKey(published("LITELLM_MASTER_KEY"))).isTrue();
        assertThat(PublishedDevSecrets.isPublishedBootstrapPassword(published("TINFIRM_BOOTSTRAP_ADMIN_PASSWORD")))
            .isTrue();

        assertThat(PublishedDevSecrets.isPublishedVaultKey(new byte[32])).isFalse();
        assertThat(PublishedDevSecrets.isPublishedLiteLlmMasterKey("sk-something-else")).isFalse();
        assertThat(PublishedDevSecrets.isPublishedBootstrapPassword(null)).isFalse();
    }

    @Test
    void localhostUrlsAreTheDevelopmentOnes() {
        for (String url : List.of("http://localhost:8080", "https://LOCALHOST", "http://app.localhost:4200",
                "http://127.0.0.1:8080", "http://127.1.2.3", "http://[::1]:8080")) {
            assertThat(PublishedDevSecrets.localUrl(URI.create(url))).as(url).isTrue();
        }
        for (String url : List.of("https://tinfirm.example.com", "http://10.0.0.5:8080", "http://localhost.example.com",
                "http://[2001:db8::1]", "http://127.example.com")) {
            assertThat(PublishedDevSecrets.localUrl(URI.create(url))).as(url).isFalse();
        }
        assertThat(PublishedDevSecrets.localUrl(null)).isFalse();
    }

    @Test
    void refusesOffLocalhostOnly() {
        PublishedDevSecrets.refuseOffLocalhost(URI.create("http://localhost:8080"), "X", "Fix it.");
        assertThatThrownBy(() -> PublishedDevSecrets.refuseOffLocalhost(URI.create("https://tinfirm.example.com"),
                "TINFIRM_BOOTSTRAP_ADMIN_PASSWORD", "Fix it."))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TINFIRM_BOOTSTRAP_ADMIN_PASSWORD")
            .hasMessageContaining("not a localhost URL");
    }
}
