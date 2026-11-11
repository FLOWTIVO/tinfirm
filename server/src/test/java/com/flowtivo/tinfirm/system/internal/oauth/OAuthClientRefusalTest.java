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
 * A token endpoint's refusal names its OAuth error, so that a refused app (its client ID or secret) is told from a
 * refused grant; Slack's own error code is named too when it is one Tinfirm knows, and nothing else of the answer.
 * TEST FIXTURE ONLY: every secret and token here is a test value.
 */
class OAuthClientRefusalTest {

    private static final Instant NOW = Instant.parse("2026-11-11T10:00:00Z");

    private static OutboundHttp http() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new OutboundHttp(new OutboundProperties(true, false, Duration.ofSeconds(5), Duration.ofSeconds(10),
                Set.of("localhost"), Set.of()), environment,
                new DefaultListableBeanFactory().getBeanProvider(PersonalStubHosts.class));
    }

    /** Slack's {@code oauth.v2.access} answering {@code 200} with the body, for a client with a secret. */
    private static CallResult<OAuthTokens> slackExchange(Map<String, Object> answer) {
        String api = "/slack-" + UUID.randomUUID().toString().substring(0, 8) + "/api";
        JiraStub.server().stubFor(post(urlEqualTo(api + "/oauth.v2.access")).willReturn(JiraStub.json(200, answer)));
        OAuthProfile profile = OAuthProfile.slack("http://localhost:" + JiraStub.server().port() + api);
        return exchange(profile, new OAuthClient.Client("test-client", "test-secret", profile.secretAuth()));
    }

    /** An MCP server's token endpoint answering the status and the body, for a public client. */
    private static CallResult<OAuthTokens> mcpExchange(int status, Map<String, Object> answer) {
        String path = "/mcp-" + UUID.randomUUID().toString().substring(0, 8) + "/token";
        JiraStub.server().stubFor(post(urlEqualTo(path)).willReturn(JiraStub.json(status, answer)));
        String base = "http://localhost:" + JiraStub.server().port();
        OAuthProfile profile = OAuthProfile.mcp(base + "/mcp", base + "/authorize", base + path, null, null);
        return exchange(profile, new OAuthClient.Client("test-client", null, profile.secretAuth()));
    }

    private static CallResult<OAuthTokens> exchange(OAuthProfile profile, OAuthClient.Client client) {
        return new OAuthClient(http()).exchange(profile, client, "test-code",
                "https://tinfirm.example.com/api/oauth/callback", "test-verifier", NOW);
    }

    @Test
    void slackRefusingTheClientSecretIsTheAppsRefusalAndNamesSlacksError() {
        CallResult<OAuthTokens> tokens = slackExchange(Map.of("ok", false, "error", "bad_client_secret"));

        CallResult.Failure failure = tokens.failure();
        assertThat(failure).isNotNull();
        assertThat(failure.kind()).isEqualTo(CallResult.Kind.UNAUTHORIZED);
        assertThat(failure.oauthError()).isEqualTo("invalid_client");
        assertThat(failure.clientRefused()).isTrue();
        assertThat(failure.message()).isEqualTo("The token endpoint answered 200 (invalid_client, Slack's "
                + "bad_client_secret)");
    }

    @Test
    void slackRefusingTheClientIdIsTheAppsRefusalToo() {
        CallResult.Failure failure = slackExchange(Map.of("ok", false, "error", "invalid_client_id")).failure();

        assertThat(failure).isNotNull();
        assertThat(failure.clientRefused()).isTrue();
        assertThat(failure.message()).endsWith("(invalid_client, Slack's invalid_client_id)");
    }

    @Test
    void slackRefusingTheCodeIsTheGrantsRefusal() {
        CallResult.Failure failure = slackExchange(Map.of("ok", false, "error", "invalid_code")).failure();

        assertThat(failure).isNotNull();
        assertThat(failure.kind()).isEqualTo(CallResult.Kind.UNAUTHORIZED);
        assertThat(failure.oauthError()).isEqualTo("invalid_grant");
        assertThat(failure.clientRefused()).isFalse();
        assertThat(failure.message()).endsWith("(invalid_grant, Slack's invalid_code)");
    }

    @Test
    void anErrorOfSlacksThatTinfirmDoesNotKnowIsNotRepeated() {
        CallResult.Failure failure = slackExchange(Map.of("ok", false, "error", "xoxp-test-reflected-token"))
            .failure();

        assertThat(failure).isNotNull();
        assertThat(failure.kind()).isEqualTo(CallResult.Kind.INVALID);
        assertThat(failure.clientRefused()).isFalse();
        assertThat(failure.message()).isEqualTo("The token endpoint answered 200 (invalid_request)")
            .doesNotContain("xoxp");
    }

    @Test
    void anotherServersRefusedClientIsTheAppsRefusal() {
        CallResult.Failure failure = mcpExchange(401, Map.of("error", "invalid_client")).failure();

        assertThat(failure).isNotNull();
        assertThat(failure.oauthError()).isEqualTo("invalid_client");
        assertThat(failure.clientRefused()).isTrue();
        assertThat(failure.message()).isEqualTo("The token endpoint answered 401 (invalid_client)");
    }

    @Test
    void anErrorOutsideRfc6749NamesNoOAuthError() {
        CallResult.Failure failure = mcpExchange(400, Map.of("error", "something_else")).failure();

        assertThat(failure).isNotNull();
        assertThat(failure.oauthError()).isNull();
        assertThat(failure.clientRefused()).isFalse();
        assertThat(failure.message()).isEqualTo("The token endpoint answered 400 (unknown_error)");
    }
}
