package com.flowtivo.tinfirm.system.internal.oauth;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.OAuthTokens;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.http.OutboundProperties;
import com.flowtivo.tinfirm.system.internal.http.PersonalStubHosts;

/**
 * A token endpoint's tokens are at most 4 KiB each (the security review of 2026-10-27, L14): a longer access or refresh
 * token is refused and nothing is kept. TEST FIXTURE ONLY: every secret and token here is a test value.
 */
class OAuthClientTokenSizeTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    private static OutboundHttp http() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new OutboundHttp(new OutboundProperties(true, false, Duration.ofSeconds(5), Duration.ofSeconds(10),
                Set.of("localhost"), Set.of()), environment,
                new DefaultListableBeanFactory().getBeanProvider(PersonalStubHosts.class));
    }

    private static CallResult<OAuthTokens> exchange(Map<String, Object> answer) {
        String path = "/mcp-" + UUID.randomUUID().toString().substring(0, 8) + "/token";
        JiraStub.server().stubFor(post(urlEqualTo(path)).willReturn(JiraStub.json(200, answer)));
        String base = "http://localhost:" + JiraStub.server().port();
        OAuthProfile profile = OAuthProfile.mcp(base + "/mcp", base + "/authorize", base + path, null, null);
        return new OAuthClient(http()).exchange(profile, new OAuthClient.Client("test-client", null,
                profile.secretAuth()), "test-code", "https://tinfirm.example.com/api/oauth/callback", "test-verifier",
                NOW);
    }

    @Test
    void tokensOfAtMost4KiBAreTaken() {
        CallResult<OAuthTokens> tokens = exchange(Map.of("access_token", "a".repeat(OAuthClient.MAX_TOKEN_BYTES),
                "refresh_token", "r".repeat(OAuthClient.MAX_TOKEN_BYTES), "token_type", "Bearer", "expires_in", 3600));

        assertThat(tokens.failure()).as("%s", tokens.failureMessage()).isNull();
        assertThat(tokens.value().accessToken()).hasSize(OAuthClient.MAX_TOKEN_BYTES);
    }

    @Test
    void aLongerAccessOrRefreshTokenIsRefused() {
        for (Map<String, Object> answer : java.util.List.<Map<String, Object>>of(
                Map.of("access_token", "a".repeat(OAuthClient.MAX_TOKEN_BYTES + 1), "token_type", "Bearer"),
                Map.of("access_token", "test-access", "refresh_token", "r".repeat(OAuthClient.MAX_TOKEN_BYTES + 1),
                        "token_type", "Bearer"),
                // Counted in bytes: half the cap in two-byte characters, and one more.
                Map.of("access_token", "é".repeat(OAuthClient.MAX_TOKEN_BYTES / 2 + 1), "token_type", "Bearer"))) {
            CallResult<OAuthTokens> tokens = exchange(answer);

            assertThat(tokens.failure()).isNotNull();
            assertThat(tokens.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
            assertThat(tokens.failureMessage()).isEqualTo("The token endpoint issued a token longer than "
                    + OAuthClient.MAX_TOKEN_BYTES / 1024 + " KiB, which Tinfirm does not keep");
        }
    }
}
