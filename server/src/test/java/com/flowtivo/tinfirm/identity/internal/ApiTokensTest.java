package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.security.ApiScope;

class ApiTokensTest {

    @Test
    void generatesPrefixedBase64UrlTokensOf43Characters() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String token = ApiTokens.generate();
            assertThat(token).matches("^tfp_[A-Za-z0-9_-]{43}$").hasSize(47);
            assertThat(ApiTokens.isWellFormed(token)).isTrue();
            assertThat(ApiTokens.hasPrefix(token)).isTrue();
            assertThat(seen.add(token)).as("unique").isTrue();
        }
    }

    @Test
    void hashesAsLowerCaseHexSha256() {
        // The FIPS 180-2 test vector.
        assertThat(ApiTokens.hash("abc")).isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        String token = ApiTokens.generate();
        assertThat(ApiTokens.hash(token)).matches("^[0-9a-f]{64}$").isEqualTo(ApiTokens.hash(token));
        assertThat(ApiTokens.hash(token)).isNotEqualTo(ApiTokens.hash(ApiTokens.generate()));
    }

    @Test
    void rejectsValuesOutsideTheFormat() {
        String body = "A".repeat(43);
        assertThat(ApiTokens.isWellFormed("tfp_" + body)).isTrue();
        assertThat(ApiTokens.isWellFormed(null)).isFalse();
        assertThat(ApiTokens.isWellFormed("tfp_" + "A".repeat(42))).isFalse();
        assertThat(ApiTokens.isWellFormed("tfp_" + "A".repeat(44))).isFalse();
        assertThat(ApiTokens.isWellFormed("tfp_" + "A".repeat(42) + "=")).isFalse();
        assertThat(ApiTokens.isWellFormed("tfp_" + "A".repeat(42) + "+")).isFalse();
        assertThat(ApiTokens.isWellFormed("tfr_" + body)).as("a runner host token").isFalse();
        assertThat(ApiTokens.isWellFormed("TFP_" + body)).isFalse();
        assertThat(ApiTokens.isWellFormed(" tfp_" + body)).isFalse();
        assertThat(ApiTokens.isWellFormed("tfp_" + body + "\n")).isFalse();
        assertThat(ApiTokens.hasPrefix("tfp_x")).isTrue();
        assertThat(ApiTokens.hasPrefix("eyJhbGciOi.x.y")).isFalse();
        assertThat(ApiTokens.hasPrefix(null)).isFalse();
    }

    @Test
    void readsTheBearerSchemeOnly() {
        assertThat(ApiTokens.bearerOf("Bearer tfp_x")).contains("tfp_x");
        assertThat(ApiTokens.bearerOf("bearer  tfp_x ")).contains("tfp_x");
        assertThat(ApiTokens.bearerOf("BEARER tfp_x")).contains("tfp_x");
        assertThat(ApiTokens.bearerOf("Bearer ")).contains("");
        assertThat(ApiTokens.bearerOf("Basic dXNlcjpwYXNz")).isEmpty();
        assertThat(ApiTokens.bearerOf("Bearer")).isEmpty();
        assertThat(ApiTokens.bearerOf("")).isEmpty();
        assertThat(ApiTokens.bearerOf(null)).isEmpty();
    }

    @Test
    void scopesIncludeTheLowerLevels() {
        assertThat(ApiScope.READ.includes(ApiScope.READ)).isTrue();
        assertThat(ApiScope.READ.includes(ApiScope.WRITE)).isFalse();
        assertThat(ApiScope.WRITE.includes(ApiScope.READ)).isTrue();
        assertThat(ApiScope.WRITE.includes(ApiScope.DECIDE)).isFalse();
        assertThat(ApiScope.DECIDE.includes(ApiScope.WRITE)).isTrue();
        assertThat(ApiScope.DECIDE.includes(ApiScope.READ)).isTrue();
        assertThat(ApiScope.DECIDE.oauthScope()).isEqualTo("tinfirm:decide");
    }
}
