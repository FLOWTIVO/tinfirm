package com.flowtivo.tinfirm.system.internal.github;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** ADR-0116: a GitHub App's key read from PEM, PKCS#1 or PKCS#8, and its JWT signed RS256 with the JDK. */
class GitHubAppClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static KeyPair keys(int bits) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(bits);
        return generator.generateKeyPair();
    }

    private static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END " + type
                + "-----";
    }

    @Test
    void readsPkcs1AndPkcs8AndRefusesTheRest() throws Exception {
        KeyPair pair = keys(2048);
        byte[] pkcs8 = pair.getPrivate().getEncoded();
        byte[] pkcs1 = Arrays.copyOfRange(pkcs8, 26, pkcs8.length);
        assertThat(GitHubAppClient.pkcs1ToPkcs8(pkcs1)).isEqualTo(pkcs8);
        assertThat(GitHubAppClient.parse(pem("PRIVATE KEY", pkcs8))).isInstanceOf(GitHubAppClient.Key.class);
        assertThat(GitHubAppClient.parse(pem("RSA PRIVATE KEY", pkcs1))).isInstanceOf(GitHubAppClient.Key.class);
        assertThat(GitHubAppClient.parse("not a key")).isInstanceOf(GitHubAppClient.BadKey.class);
        assertThat(GitHubAppClient.parse(pem("RSA PRIVATE KEY", pkcs8))).isInstanceOf(GitHubAppClient.BadKey.class);
        byte[] small = keys(1024).getPrivate().getEncoded();
        assertThat(GitHubAppClient.parse(pem("PRIVATE KEY", small))).isInstanceOfSatisfying(
                GitHubAppClient.BadKey.class, bad -> assertThat(bad.reason()).contains("2048"));
        // The reason never carries the key.
        assertThat(GitHubAppClient.parse(pem("PRIVATE KEY", Arrays.copyOf(pkcs8, 100))).toString())
            .doesNotContain(Base64.getEncoder().encodeToString(Arrays.copyOf(pkcs8, 30)));
    }

    @Test
    void signsAJwtOfTheAppForNineMinutesFromAMinuteAgo() throws Exception {
        KeyPair pair = keys(2048);
        Instant now = Instant.parse("2026-11-19T10:00:00Z");
        String jwt = GitHubAppClient.jwt(424242, pair.getPrivate(), now);
        String[] parts = jwt.split("\\.");
        assertThat(parts).hasSize(3);
        JsonNode header = JSON.readTree(Base64.getUrlDecoder().decode(parts[0]));
        JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
        assertThat(header.get("alg").asString()).isEqualTo("RS256");
        assertThat(claims.get("iss").asLong()).isEqualTo(424242);
        assertThat(claims.get("iat").asLong()).isEqualTo(now.getEpochSecond() - 60);
        assertThat(claims.get("exp").asLong()).isEqualTo(now.getEpochSecond() + 540);
        Signature verify = Signature.getInstance("SHA256withRSA");
        verify.initVerify(pair.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }
}
