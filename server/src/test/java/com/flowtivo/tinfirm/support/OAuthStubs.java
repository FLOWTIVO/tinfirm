package com.flowtivo.tinfirm.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * OAuth accounts (the package 1 contract, §3.8) on the Jira Data Center stub: the app an admin set, the token endpoint
 * of Jira's OAuth 2.0 provider, and a person connecting through a sign-in and the one callback.
 * TEST FIXTURES ONLY: every client secret, code and token here is a test value.
 */
public final class OAuthStubs {

    public static final String JIRA_CLIENT_ID = "test-jira-oauth-client";

    public static final String JIRA_CLIENT_SECRET = "test-jira-oauth-client-secret";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The state of a {@link #jiraRotates} scenario once its refresh token is spent. */
    private static final String SPENT = "spent";

    private OAuthStubs() {
    }

    /** An admin sets the system's app: the OAuth client registered at Jira ({@code PUT .../systems/{id}/app}). */
    public static void jiraClient(ApiClient admin, String systemPath) {
        ApiResponse changed = admin.put(systemPath + "/app", Map.of("clientId", JIRA_CLIENT_ID, "clientSecret",
                JIRA_CLIENT_SECRET));
        assertThat(changed.status()).as("OAuth client: %s", changed).isEqualTo(200);
    }

    /**
     * Jira's token endpoint answers a request whose form contains {@code match} (a code, a refresh token) with these
     * tokens; a later stub of the same match wins.
     *
     * @param refreshToken null: the answer names none (the old one stays)
     */
    public static void jiraTokens(JiraStub.Instance jira, String match, String accessToken,
            @Nullable String refreshToken, int expiresIn) {
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("access_token", accessToken);
        if (refreshToken != null) {
            tokens.put("refresh_token", refreshToken);
        }
        tokens.put("token_type", "Bearer");
        tokens.put("expires_in", expiresIn);
        tokens.put("scope", "WRITE");
        JiraStub.server().stubFor(post(urlEqualTo(tokenPath(jira))).withRequestBody(containing(match))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(JSON.writeValueAsString(tokens))));
    }

    /** Jira's token endpoint refuses a request whose form contains {@code match}, before any other stub of it. */
    public static void jiraRefuses(JiraStub.Instance jira, String match, String error) {
        jiraRefuses(jira, match, error, 0);
    }

    /** As {@link #jiraRefuses(JiraStub.Instance, String, String)}, answering after {@code delayMillis}. */
    public static void jiraRefuses(JiraStub.Instance jira, String match, String error, int delayMillis) {
        JiraStub.server().stubFor(post(urlEqualTo(tokenPath(jira))).atPriority(1).withRequestBody(containing(match))
            .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                .withBody(JSON.writeValueAsString(Map.of("error", error)))
                .withFixedDelay(delayMillis)));
    }

    /**
     * Jira's token endpoint rotates {@code refreshToken} as a provider that spends a refresh token on its first use
     * does: the first refresh with it is answered with these tokens after {@code delayMillis}, and every later one is
     * refused with {@code invalid_grant}. The rotation counts as spent ({@link #jiraRotationSpent}) once the first
     * refresh arrived, before its answer.
     */
    public static void jiraRotates(JiraStub.Instance jira, String refreshToken, String accessToken,
            String nextRefreshToken, int expiresIn, int delayMillis) {
        String match = "refresh_token=" + refreshToken;
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("access_token", accessToken);
        tokens.put("refresh_token", nextRefreshToken);
        tokens.put("token_type", "Bearer");
        tokens.put("expires_in", expiresIn);
        tokens.put("scope", "WRITE");
        JiraStub.server().stubFor(post(urlEqualTo(tokenPath(jira))).atPriority(1).inScenario(rotation(jira))
            .whenScenarioStateIs(Scenario.STARTED)
            .willSetStateTo(SPENT)
            .withRequestBody(containing(match))
            .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody(JSON.writeValueAsString(tokens))
                .withFixedDelay(delayMillis)));
        JiraStub.server().stubFor(post(urlEqualTo(tokenPath(jira))).atPriority(1).inScenario(rotation(jira))
            .whenScenarioStateIs(SPENT)
            .withRequestBody(containing(match))
            .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                .withBody(JSON.writeValueAsString(Map.of("error", "invalid_grant")))));
    }

    /** Whether the first refresh of {@link #jiraRotates} arrived: the refresh token it rotates is spent. */
    public static boolean jiraRotationSpent(JiraStub.Instance jira) {
        return JiraStub.server()
            .getAllScenarios()
            .getScenarios()
            .stream()
            .anyMatch(scenario -> scenario.getName().equals(rotation(jira)) && SPENT.equals(scenario.getState()));
    }

    private static String rotation(JiraStub.Instance jira) {
        return "rotation" + jira.path();
    }

    /**
     * The person connects their Jira account with OAuth: the sign-in, then the callback with a code that the token
     * endpoint answers with these tokens; Jira's myself names the account.
     *
     * @param accountPath the account, {@code /api/companies/{id}/me/accounts/{systemId}} or a service identity's
     */
    public static void connectJira(ApiClient person, String accountPath, JiraStub.Instance jira, String accessToken,
            String refreshToken, int expiresIn, String name) {
        String state = startSignIn(person, accountPath, "/accounts");
        String code = "test-jira-code-" + UUID.randomUUID();
        jiraTokens(jira, "code=" + code, accessToken, refreshToken, expiresIn);
        jira.myself(accessToken, name, "UTC");
        ApiResponse callback = person.get("/api/oauth/callback?code=" + code + "&state=" + state);
        assertThat(callback.status()).as("callback: %s", callback).isEqualTo(303);
        assertThat(callback.header("Location")).as("callback").startsWith("/accounts?account=connected");
    }

    /** Starts a sign-in on the account's path and answers its state. */
    public static String startSignIn(ApiClient person, String accountPath, String returnTo) {
        return startSignIn(person, accountPath, Map.of("returnTo", returnTo));
    }

    /** As {@link #startSignIn(ApiClient, String, String)}, with the request's body: a new credential's name too. */
    public static String startSignIn(ApiClient person, String accountPath, Map<String, ?> body) {
        ApiResponse started = person.post(accountPath + "/oauth", body);
        assertThat(started.status()).as("sign-in: %s", started).isEqualTo(200);
        return query(URI.create(started.json().get("authorizationUrl").asString()).getRawQuery()).get("state");
    }

    /** The forms of the refresh requests Jira's token endpoint took, oldest first. */
    public static List<Map<String, String>> jiraRefreshes(JiraStub.Instance jira) {
        return JiraStub.server()
            .findAll(postRequestedFor(urlEqualTo(tokenPath(jira))))
            .stream()
            .map(OAuthStubs::form)
            .filter(form -> "refresh_token".equals(form.get("grant_type")))
            .toList();
    }

    public static Map<String, String> form(LoggedRequest request) {
        return query(request.getBodyAsString());
    }

    /** A form or query string's parameters, decoded. */
    public static Map<String, String> query(String query) {
        Map<String, String> parameters = new LinkedHashMap<>();
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            parameters.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                    URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
        }
        return parameters;
    }

    private static String tokenPath(JiraStub.Instance jira) {
        return jira.path() + "/rest/oauth2/latest/token";
    }
}
