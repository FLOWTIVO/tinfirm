package com.flowtivo.tinfirm.system.internal.oauth;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
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
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.http.OutboundProperties;
import com.flowtivo.tinfirm.system.internal.http.PersonalStubHosts;

/**
 * The {@code client_credentials} grant (RFC 6749 §4.4) of an Atlassian service account's OAuth 2.0 credential, on a
 * stub of Atlassian's token endpoint: a form POST with the client's id and secret in the body, as Jira Cloud's profile
 * authenticates a client, and Atlassian's audience; a token without a refresh token; a refused client fails {@code UNAUTHORIZED}, without the
 * secret in the reason. TEST FIXTURE ONLY: every secret and token here is a test value.
 */
class ClientCredentialsGrantTest {

    private static final Instant NOW = Instant.parse("2026-10-26T10:00:00Z");

    private static OutboundHttp http() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new OutboundHttp(new OutboundProperties(true, false, Duration.ofSeconds(5), Duration.ofSeconds(10),
                Set.of("localhost"), Set.of()), environment,
                new DefaultListableBeanFactory().getBeanProvider(PersonalStubHosts.class));
    }

    /** Jira Cloud's profile, with its token endpoint on the stub. */
    private static OAuthProfile profile(String tokenEndpoint) {
        OAuthProfile cloud = OAuthProfile.tracker(SystemKind.JIRA_CLOUD, "https://acme.atlassian.net");
        return new OAuthProfile(cloud.kind(), cloud.authorizationEndpoint(), tokenEndpoint, null,
                cloud.defaultScopes(), cloud.authorizeParameters(), cloud.secretAuth(), null);
    }

    @Test
    void theClientMintsATokenWithItsOwnCredentials() {
        String path = "/atlassian-" + UUID.randomUUID().toString().substring(0, 8) + "/oauth/token";
        JiraStub.server().stubFor(post(urlEqualTo(path)).willReturn(JiraStub.json(200, Map.of("access_token",
                "test-minted", "token_type", "Bearer", "expires_in", 3600, "scope", "read:jira-work read:jira-user",
                "refresh_token", "test-never-kept"))));
        OAuthProfile profile = profile("http://localhost:" + JiraStub.server().port() + path);

        CallResult<OAuthTokens> minted = new OAuthClient(http()).clientCredentials(profile,
                new OAuthClient.Client("test-client", "test-secret", profile.secretAuth()), NOW);

        assertThat(minted.failure()).as("%s", minted.failureMessage()).isNull();
        assertThat(minted.value()).isEqualTo(new OAuthTokens("test-minted", null, "Bearer",
                NOW.plusSeconds(3600), "read:jira-work read:jira-user"));
        JiraStub.server().verify(postRequestedFor(urlEqualTo(path))
            .withHeader("Content-Type", equalTo("application/x-www-form-urlencoded"))
            .withRequestBody(containing("grant_type=client_credentials"))
            .withRequestBody(containing("audience=api.atlassian.com"))
            .withRequestBody(containing("client_id=test-client"))
            .withRequestBody(containing("client_secret=test-secret")));
    }

    @Test
    void aRefusedClientFailsUnauthorizedWithoutItsSecret() {
        String path = "/atlassian-" + UUID.randomUUID().toString().substring(0, 8) + "/oauth/token";
        JiraStub.server().stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(401)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"error\":\"access_denied\",\"error_description\":\"Unauthorized test-secret\"}")));
        OAuthProfile profile = profile("http://localhost:" + JiraStub.server().port() + path);

        CallResult<OAuthTokens> minted = new OAuthClient(http()).clientCredentials(profile,
                new OAuthClient.Client("test-client", "test-secret", profile.secretAuth()), NOW);

        assertThat(minted.failure()).isNotNull();
        assertThat(minted.failure().kind()).isEqualTo(CallResult.Kind.UNAUTHORIZED);
        assertThat(minted.failureMessage()).isEqualTo("The token endpoint answered 401 (access_denied)")
            .doesNotContain("test-secret");
    }

    @Test
    void aTokenWithoutALifetimeIsRefused() {
        String path = "/atlassian-" + UUID.randomUUID().toString().substring(0, 8) + "/oauth/token";
        JiraStub.server().stubFor(post(urlEqualTo(path)).willReturn(JiraStub.json(200, Map.of("access_token",
                "test-minted", "token_type", "Bearer"))));
        OAuthProfile profile = profile("http://localhost:" + JiraStub.server().port() + path);

        CallResult<OAuthTokens> minted = new OAuthClient(http()).clientCredentials(profile,
                new OAuthClient.Client("test-client", "test-secret", profile.secretAuth()), NOW);

        assertThat(minted.failure()).isNotNull();
        assertThat(minted.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
        assertThat(minted.failureMessage()).contains("without expires_in");
    }

    @Test
    void aClientWithoutASecretSendsNothing() {
        OAuthProfile profile = profile("http://localhost:" + JiraStub.server().port() + "/never");

        CallResult<OAuthTokens> minted = new OAuthClient(http()).clientCredentials(profile,
                new OAuthClient.Client("test-client", null, profile.secretAuth()), NOW);

        assertThat(minted.failure()).isNotNull();
        assertThat(minted.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
        JiraStub.server().verify(0, postRequestedFor(urlEqualTo("/never")));
    }
}
