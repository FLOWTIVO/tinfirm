package com.flowtivo.tinfirm.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Microsoft Teams' Bot Framework for the tests (TEST FIXTURE ONLY, the package 5 contract §9's
 * {@code FakeBotFramework}): one WireMock server stands in for Microsoft's login (the bot's {@code client_credentials}
 * token), the Bot Framework's OpenID metadata and signing keys (a test JWK set: one key endorsed for {@code msteams},
 * one for another channel), and the connector at the Teams systems' fixed base URL (activities, the roster, personal
 * chats). Activities are posted to the server with a bearer JWT the test keys sign, as the Bot Framework's connector
 * does. Every test gets a tenant and app of its own ({@link #tenant()}). Every key and password is a test value.
 */
public final class FakeBotFramework {

    private static final WireMockServer SERVER = new WireMockServer(StubServers.options()
        .dynamicPort()
        .globalTemplating(true));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final RSAKey TEAMS_KEY = key("test-teams-key");

    private static final RSAKey OTHER_CHANNEL_KEY = key("test-other-channel-key");

    /** Signs, with a kid the JWK set does not name. */
    private static final RSAKey UNKNOWN_KEY = key("test-unknown-key");

    static {
        SERVER.start();
        Map<String, Object> teams = new LinkedHashMap<>(TEAMS_KEY.toPublicJWK().toJSONObject());
        teams.put("endorsements", List.of("msteams", "skype"));
        Map<String, Object> other = new LinkedHashMap<>(OTHER_CHANNEL_KEY.toPublicJWK().toJSONObject());
        other.put("endorsements", List.of("webchat"));
        SERVER.stubFor(get(urlEqualTo("/v1/.well-known/openidconfiguration"))
            .willReturn(json(Map.of("issuer", "https://api.botframework.com", "jwks_uri", url() + "/v1/keys",
                    "id_token_signing_alg_values_supported", List.of("RS256")))));
        SERVER.stubFor(get(urlEqualTo("/v1/keys")).willReturn(json(Map.of("keys", List.of(teams, other)))));
    }

    private FakeBotFramework() {
    }

    private static RSAKey key(String kid) {
        try {
            return new RSAKeyGenerator(2048).keyID(kid).generate();
        }
        catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static WireMockServer server() {
        return SERVER;
    }

    /** The connector's origin in the tests: the Teams systems' base URL, and every activity's {@code serviceUrl}. */
    public static String url() {
        return "http://localhost:" + SERVER.port();
    }

    /** The OpenID metadata's URL. */
    public static String openIdUrl() {
        return url() + "/v1/.well-known/openidconfiguration";
    }

    /** A tenant with its Azure Bot of its own: the app's password is accepted by the login. */
    public static Tenant tenant() {
        Tenant tenant = new Tenant(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                "test-app-password-" + UUID.randomUUID());
        tenant.acceptPassword(tenant.password);
        tenant.connector();
        return tenant;
    }

    /** A Teams tenant of a test, and its bot. */
    public static final class Tenant {

        public final String tenantId;

        public final String appId;

        public final String password;

        private Tenant(String tenantId, String appId, String password) {
            this.tenantId = tenantId;
            this.appId = appId;
            this.password = password;
        }

        /** Microsoft's login takes the password for the app. */
        public void acceptPassword(String secret) {
            SERVER.stubFor(post(urlEqualTo("/" + tenantId + "/oauth2/v2.0/token"))
                .withRequestBody(containing("client_secret=" + URLEncoder.encode(secret, StandardCharsets.UTF_8)))
                .willReturn(json(Map.of("token_type", "Bearer", "expires_in", 3600, "access_token",
                        "test-bot-token-" + appId))));
        }

        /** Microsoft's login refuses every other password of the app. */
        public void refusePasswords() {
            SERVER.stubFor(post(urlEqualTo("/" + tenantId + "/oauth2/v2.0/token"))
                .atPriority(10)
                .willReturn(aResponse().withStatus(401).withHeader("Content-Type", "application/json")
                    .withBody("{\"error\":\"invalid_client\"}")));
        }

        /** The connector answers the bot's posts, updates and personal chats. */
        private void connector() {
            String token = "Bearer test-bot-token-" + appId;
            SERVER.stubFor(post(urlPathMatching("/v3/conversations/[^/]+/activities"))
                .withHeader("Authorization", equalTo(token))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"1:{{randomValue length=12 type='ALPHANUMERIC'}}\"}")));
            SERVER.stubFor(put(urlPathMatching("/v3/conversations/[^/]+/activities/[^/]+"))
                .withHeader("Authorization", equalTo(token))
                .willReturn(json(Map.of("id", "updated"))));
            SERVER.stubFor(post(urlPathEqualTo("/v3/conversations"))
                .withHeader("Authorization", equalTo(token))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                    .withBody("{\"id\":\"a:dm-{{jsonPath request.body '$.members[0].id'}}\"}")));
        }

        /** A user of the tenant, as the roster answers: {@code 29:} id, Entra object id, role. */
        public TeamsUser user(String name, String role) {
            return userOf(name, role, tenantId);
        }

        public TeamsUser userOf(String name, String role, String userTenant) {
            TeamsUser user = new TeamsUser("29:" + UUID.randomUUID(), UUID.randomUUID().toString(), name, role);
            Map<String, Object> member = new LinkedHashMap<>();
            member.put("id", user.id());
            member.put("name", name);
            member.put("aadObjectId", user.aadObjectId());
            member.put("userRole", role);
            member.put("tenantId", userTenant);
            SERVER.stubFor(get(urlPathMatching("/v3/conversations/[^/]+/members/"
                    + URLEncoder.encode(user.id(), StandardCharsets.UTF_8).replace("%3A", "(:|%3A)")))
                .willReturn(json(member)));
            // The roster answers by the Entra object id too: a channel trigger's author in its channels (package 5b).
            SERVER.stubFor(get(urlPathMatching("/v3/conversations/[^/]+/members/" + user.aadObjectId()))
                .willReturn(json(member)));
            return user;
        }

        /**
         * A message mentioning the bot in a team's channel (package 5b): a new post, its conversation the channel's
         * thread of its own id ({@code {channel};messageid={id}}), or a reply in the thread of {@code rootId}.
         */
        public Map<String, Object> channelMention(TeamsUser user, String teamId, String channelId,
                @Nullable String rootId, String text) {
            Map<String, Object> activity = activity(user, "channel", channelId);
            String root = rootId != null ? rootId : (String) activity.get("id");
            activity.put("conversation", Map.of("id", channelId + ";messageid=" + root, "conversationType", "channel",
                    "tenantId", tenantId));
            activity.put("type", "message");
            activity.put("text", "<at>Tinfirm</at> " + text);
            activity.put("textFormat", "plain");
            activity.put("entities", List.of(Map.of("type", "mention", "text", "<at>Tinfirm</at>", "mentioned",
                    Map.of("id", "28:" + appId, "name", "Tinfirm"))));
            activity.put("channelData", Map.of("tenant", Map.of("id", tenantId), "team", Map.of("id", teamId),
                    "channel", Map.of("id", channelId)));
            return activity;
        }

        /** The activities the bot posted into a conversation, oldest first. */
        public List<JsonNode> posts(String conversationId) {
            String path = "/v3/conversations/" + conversationId + "/activities";
            return requests(SERVER.findAll(postRequestedFor(urlPathMatching("/v3/conversations/[^/]+/activities")))
                .stream()
                .filter(request -> java.net.URLDecoder.decode(request.getUrl(), StandardCharsets.UTF_8)
                    .startsWith(path))
                .toList());
        }

        /** Every activity the bot posted into this tenant's conversations. */
        public List<JsonNode> allPosts() {
            return requests(SERVER.findAll(postRequestedFor(urlPathMatching("/v3/conversations/[^/]+/activities"))
                .withHeader("Authorization", equalTo("Bearer test-bot-token-" + appId))));
        }

        /** The activities the bot replaced. */
        public List<JsonNode> updates() {
            return requests(SERVER.findAll(putRequestedFor(urlPathMatching("/v3/conversations/[^/]+/activities/.+"))
                .withHeader("Authorization", equalTo("Bearer test-bot-token-" + appId))));
        }

        // ---- activities --------------------------------------------------------------------------------------------

        /** A message in the user's personal chat with the bot. */
        public Map<String, Object> personalMessage(TeamsUser user, String text) {
            Map<String, Object> activity = activity(user, "personal", "a:dm-" + user.id());
            activity.put("type", "message");
            activity.put("text", text);
            activity.put("textFormat", "plain");
            return activity;
        }

        /** An {@code Action.Submit} press on a card the bot posted. */
        public Map<String, Object> submit(TeamsUser user, String conversationId, String replyToId,
                Map<String, Object> value) {
            Map<String, Object> activity = activity(user, "personal", conversationId);
            activity.put("type", "message");
            activity.put("replyToId", replyToId);
            activity.put("value", value);
            return activity;
        }

        public Map<String, Object> activity(TeamsUser user, String conversationType, String conversationId) {
            Map<String, Object> activity = new LinkedHashMap<>();
            activity.put("id", "1" + Long.toString(System.nanoTime(), 36));
            activity.put("timestamp", Instant.now().toString());
            activity.put("serviceUrl", url() + "/");
            activity.put("channelId", "msteams");
            Map<String, Object> from = new LinkedHashMap<>();
            from.put("id", user.id());
            from.put("name", user.name());
            from.put("aadObjectId", user.aadObjectId());
            activity.put("from", from);
            activity.put("conversation", Map.of("id", conversationId, "conversationType", conversationType,
                    "tenantId", tenantId));
            activity.put("recipient", Map.of("id", "28:" + appId, "name", "Tinfirm"));
            activity.put("channelData", Map.of("tenant", Map.of("id", tenantId)));
            return activity;
        }

        /** A token as the connector signs it for this app, for the activity's {@code serviceUrl}. */
        public String token() {
            return FakeBotFramework.token(TEAMS_KEY, appId, url() + "/", Instant.now().plusSeconds(3600));
        }

        /** Posts the activity to the system's messaging endpoint with the token. */
        public Answer deliver(int port, UUID systemId, Map<String, Object> activity, @Nullable String token) {
            return FakeBotFramework.deliver(port, systemId, activity, token);
        }

        public Answer deliver(int port, UUID systemId, Map<String, Object> activity) {
            return deliver(port, systemId, activity, token());
        }
    }

    /** A Teams user: their {@code 29:} id, their Entra object id. */
    public record TeamsUser(String id, String aadObjectId, String name, String role) {
    }

    /** What the server answered. */
    public record Answer(int status, String body) {
    }

    /** A token signed by the key endorsed for {@code msteams}. */
    public static String token(String audience, String serviceUrl, Instant expires) {
        return token(TEAMS_KEY, audience, serviceUrl, expires);
    }

    /** A token signed by a key endorsed for another channel only. */
    public static String tokenOfOtherChannel(String audience, String serviceUrl) {
        return token(OTHER_CHANNEL_KEY, audience, serviceUrl, Instant.now().plusSeconds(3600));
    }

    /** A token signed by a key the JWK set does not name. */
    public static String tokenOfUnknownKey(String audience, String serviceUrl) {
        return token(UNKNOWN_KEY, audience, serviceUrl, Instant.now().plusSeconds(3600));
    }

    /** A token with any issuer. */
    public static String tokenOfIssuer(String issuer, String audience, String serviceUrl) {
        return sign(TEAMS_KEY, new JWTClaimsSet.Builder().issuer(issuer).audience(audience)
            .expirationTime(Date.from(Instant.now().plusSeconds(3600)))
            .notBeforeTime(Date.from(Instant.now().minusSeconds(60)))
            .claim("serviceurl", serviceUrl)
            .build());
    }

    private static String token(RSAKey key, String audience, String serviceUrl, Instant expires) {
        return sign(key, new JWTClaimsSet.Builder().issuer("https://api.botframework.com").audience(audience)
            .expirationTime(Date.from(expires))
            .notBeforeTime(Date.from(Instant.now().minusSeconds(60)))
            .issueTime(Date.from(Instant.now().minusSeconds(60)))
            .claim("serviceurl", serviceUrl)
            .build());
    }

    private static String sign(RSAKey key, JWTClaimsSet claims) {
        try {
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                    claims);
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        }
        catch (JOSEException ex) {
            throw new IllegalStateException(ex);
        }
    }

    public static Answer deliver(int port, UUID systemId, Map<String, Object> activity, @Nullable String token) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
                + "/api/chat/teams/" + systemId + "/messages"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(activity)));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        try {
            HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
            return new Answer(response.statusCode(), response.body());
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static List<JsonNode> requests(List<LoggedRequest> requests) {
        List<JsonNode> bodies = new ArrayList<>();
        requests.stream()
            .sorted(java.util.Comparator.comparing(LoggedRequest::getLoggedDate))
            .forEach(request -> bodies.add(JSON.readTree(request.getBodyAsString())));
        return bodies;
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(Object body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody(JSON.writeValueAsString(body));
    }
}
