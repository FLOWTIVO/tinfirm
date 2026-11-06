package com.flowtivo.tinfirm.identity.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.shared.security.ApiScope;
import com.flowtivo.tinfirm.shared.security.BearerGrant;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Tinfirm's OAuth 2.1 authorization server for people's MCP clients (ADR-0045), against the running server: a client
 * known by its Client ID Metadata Document (served by a WireMock stub on {@code localhost}, which the test profile
 * lets through the outbound guard; every other private host stays refused), the login round trip, the consent, PKCE,
 * the code exchange, refresh with rotation, revocation, the audience per company, and "Connected apps".
 * <p>
 * The issuer is the test profile's {@code TINFIRM_PUBLIC_URL}, {@code http://localhost:8080}, while the server runs on
 * a random port: redirects name the issuer, and the test follows them on its own port.
 */
@ExtendWith(EnterpriseLicence.class)
class OAuthAuthorizationServerIntegrationTest extends IntegrationTest {

    private static final String ISSUER = "http://localhost:8080";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final WireMockServer CIMD = new WireMockServer(WireMockConfiguration.options().dynamicPort());

    private static final SecureRandom RANDOM = new SecureRandom();

    static {
        CIMD.start();
    }

    @Autowired
    private List<BearerCredentialResolver> resolvers;

    @AfterAll
    static void stopStub() {
        CIMD.stop();
    }

    // ---- fixtures --------------------------------------------------------------------------------------------------

    private record Member(UUID id, String email, ApiClient session) {
    }

    private record Tokens(String access, String refresh, String scope) {
    }

    private Member newMember(UUID companyId, String role) {
        String email = uniqueEmail("oauth");
        UUID id = createUser(admin(), email, false);
        if (companyId != null) {
            addMember(admin(), companyId, email, role);
        }
        return new Member(id, email, loginAs(email));
    }

    /** A client metadata document at {@code http://localhost:<stub>/<random>.json}; returns the client id. */
    private static String publishClient(String name, String... redirectUris) {
        String path = "/client-" + UUID.randomUUID() + ".json";
        String clientId = "http://localhost:" + CIMD.port() + path;
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("client_id", clientId);
        document.put("client_name", name);
        document.put("redirect_uris", List.of(redirectUris));
        document.put("grant_types", List.of("authorization_code", "refresh_token"));
        document.put("response_types", List.of("code"));
        document.put("token_endpoint_auth_method", "none");
        CIMD.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withHeader("Cache-Control", "max-age=600")
            .withBody(JSON.writeValueAsString(document))));
        return clientId;
    }

    private static String resource(UUID companyId) {
        return ISSUER + "/api/companies/" + companyId + "/mcp";
    }

    private static String verifier() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String challenge(String verifier) {
        try {
            return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        }
        catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String form(Map<String, ?> parameters) {
        List<String> pairs = new ArrayList<>();
        parameters.forEach((name, value) -> {
            List<?> values = value instanceof List<?> list ? list : List.of(value);
            for (Object single : values) {
                pairs.add(URLEncoder.encode(name, StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(String.valueOf(single), StandardCharsets.UTF_8));
            }
        });
        return String.join("&", pairs);
    }

    private static String authorizePath(String clientId, String redirectUri, String scope, String resource,
            String state, String verifier) {
        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("response_type", "code");
        parameters.put("client_id", clientId);
        parameters.put("redirect_uri", redirectUri);
        parameters.put("scope", scope);
        parameters.put("state", state);
        parameters.put("code_challenge", challenge(verifier));
        parameters.put("code_challenge_method", "S256");
        parameters.put("resource", resource);
        return "/oauth2/authorize?" + form(parameters);
    }

    /** The path and query of an absolute URL on the issuer, to follow it on the test's port. */
    private static String local(String location) {
        assertThat(location).startsWith(ISSUER + "/");
        URI uri = URI.create(location);
        return uri.getRawQuery() != null ? uri.getRawPath() + "?" + uri.getRawQuery() : uri.getRawPath();
    }

    private static Map<String, String> query(String url) {
        String raw = URI.create(url).getRawQuery();
        Map<String, String> parameters = new LinkedHashMap<>();
        if (raw != null) {
            for (String pair : raw.split("&")) {
                int equals = pair.indexOf('=');
                parameters.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return parameters;
    }

    private ApiResponse postForm(ApiClient client, String path, Map<String, ?> parameters) {
        return client.send("POST", path, form(parameters),
                Map.of("Content-Type", "application/x-www-form-urlencoded"));
    }

    /** Signs in through the consent page: authorize, consent to {@code approved}, and return the code's redirect. */
    private String authorizeAndConsent(Member member, String clientId, String redirectUri, String requested,
            List<String> approved, UUID companyId, String verifier) {
        ApiResponse authorize = member.session()
            .get(authorizePath(clientId, redirectUri, requested, resource(companyId), "client-state", verifier));
        assertThat(authorize.status()).as("%s", authorize).isEqualTo(302);
        String consentPage = authorize.header("Location");
        assertThat(consentPage).startsWith(ISSUER + "/authorize?");
        Map<String, String> consent = query(consentPage);
        ApiResponse decided = postForm(member.session(), "/oauth2/authorize",
                Map.of("client_id", consent.get("client_id"), "state", consent.get("state"), "scope", approved));
        assertThat(decided.status()).as("%s", decided).isEqualTo(200);
        return decided.json().get("redirectUri").asString();
    }

    private Tokens exchange(String clientId, String redirectUri, String code, String verifier, UUID companyId) {
        ApiResponse token = postForm(client(), "/oauth2/token",
                Map.of("grant_type", "authorization_code", "code", code, "redirect_uri", redirectUri, "client_id",
                        clientId, "code_verifier", verifier, "resource", resource(companyId)));
        assertThat(token.status()).as("%s", token).isEqualTo(200);
        JsonNode body = token.json();
        return new Tokens(body.get("access_token").asString(), body.get("refresh_token").asString(),
                body.get("scope").asString());
    }

    private Tokens connect(Member member, String clientId, String redirectUri, List<String> scopes, UUID companyId) {
        String verifier = verifier();
        String redirect = authorizeAndConsent(member, clientId, redirectUri, String.join(" ", scopes), scopes,
                companyId, verifier);
        return exchange(clientId, redirectUri, query(redirect).get("code"), verifier, companyId);
    }

    private ApiResponse mcp(String token, UUID companyId) {
        return client().send("POST", "/api/companies/" + companyId + "/mcp",
                Map.of("jsonrpc", "2.0", "id", 1, "method", "ping"), Map.of("Authorization", "Bearer " + token));
    }

    private static JsonNode claims(String jwt) {
        return JSON.readTree(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]));
    }

    /** The grant the MCP chain resolves a token to, for a request to the company's endpoint. */
    private Optional<BearerGrant> grantOf(String token, UUID companyId) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/companies/" + companyId + "/mcp");
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
        try {
            BearerCredentialResolver resolver = resolvers.stream()
                .filter(candidate -> candidate.supports(token))
                .findFirst()
                .orElseThrow();
            return resolver.resolve(token).map(ResolvedBearer::grant);
        }
        finally {
            RequestContextHolder.resetRequestAttributes();
        }
    }

    private static List<String> names(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(JsonNode::asString).toList();
    }

    /** Starts an authorization that asks for {@code requested}; returns the consent page's query. */
    private Map<String, String> authorize(Member member, String clientId, String redirectUri, String requested,
            UUID companyId) {
        ApiResponse authorize = member.session()
            .get(authorizePath(clientId, redirectUri, requested, resource(companyId), "client-state", verifier()));
        assertThat(authorize.status()).as("%s", authorize).isEqualTo(302);
        assertThat(authorize.header("Location")).startsWith(ISSUER + "/authorize?");
        return query(authorize.header("Location"));
    }

    /** What the consent page reads about a pending request. */
    private JsonNode consentDetails(Member member, String clientId, String state) {
        ApiResponse details = member.session()
            .get("/api/me/oauth/consent?clientId=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) + "&state="
                    + URLEncoder.encode(state, StandardCharsets.UTF_8));
        assertThat(details.status()).as("%s", details).isEqualTo(200);
        return details.json();
    }

    /** A request for {@code requested}, consented to {@code approved}, exchanged: the token response. */
    private JsonNode consentAndExchange(Member member, String clientId, String redirectUri, String requested,
            List<String> approved, UUID companyId) {
        String verifier = verifier();
        String redirect = authorizeAndConsent(member, clientId, redirectUri, requested, approved, companyId, verifier);
        ApiResponse token = postForm(client(), "/oauth2/token",
                Map.of("grant_type", "authorization_code", "code", query(redirect).get("code"), "redirect_uri",
                        redirectUri, "client_id", clientId, "code_verifier", verifier, "resource",
                        resource(companyId)));
        assertThat(token.status()).as("%s", token).isEqualTo(200);
        return token.json();
    }

    private static List<JsonNode> events(JsonNode events, String action) {
        return StreamSupport.stream(events.spliterator(), false)
            .filter(event -> event.get("action").asString().equals(action))
            .toList();
    }

    // ---- the flow --------------------------------------------------------------------------------------------------

    @Test
    void theWholeAuthorizationCodeFlowWithLoginConsentRefreshAndRevocation() {
        UUID companyId = createCompany(admin(), unique("OAuth Co"));
        Member owner = newMember(companyId, "OWNER");
        String clientId = publishClient("Test MCP Client", "http://localhost/callback", "http://127.0.0.1/callback");
        String redirectUri = "http://localhost:43123/callback";
        String verifier = verifier();
        String authorize = authorizePath(clientId, redirectUri, "tinfirm:read tinfirm:write tinfirm:decide",
                resource(companyId), "client-state", verifier);

        // Without a session: to the SPA's login, with the request in returnTo.
        ApiClient browser = client();
        ApiResponse anonymous = browser.get(authorize);
        assertThat(anonymous.status()).as("%s", anonymous).isEqualTo(302);
        String login = anonymous.header("Location");
        assertThat(login).startsWith(ISSUER + "/login?returnTo=");
        String returnTo = query(login).get("returnTo");
        assertThat(returnTo).isEqualTo(authorize);

        // The person signs in (the SPA's local login), and the SPA navigates to returnTo.
        assertThat(browser.login(owner.email(), PASSWORD).status()).isEqualTo(200);
        ApiResponse afterLogin = browser.get(returnTo);
        assertThat(afterLogin.status()).as("%s", afterLogin).isEqualTo(302);
        String consentPage = afterLogin.header("Location");
        assertThat(consentPage).startsWith(ISSUER + "/authorize?");
        Map<String, String> consent = query(consentPage);
        assertThat(consent.get("client_id")).isEqualTo(clientId);
        assertThat(consent.get("scope").split(" ")).containsExactlyInAnyOrder("tinfirm:read", "tinfirm:write",
                "tinfirm:decide");

        // The consent page's data.
        ApiResponse details = browser.get("/api/me/oauth/consent?clientId="
                + URLEncoder.encode(clientId, StandardCharsets.UTF_8) + "&state="
                + URLEncoder.encode(consent.get("state"), StandardCharsets.UTF_8) + "&scope="
                + URLEncoder.encode(consent.get("scope"), StandardCharsets.UTF_8));
        assertThat(details.status()).as("%s", details).isEqualTo(200);
        assertThat(details.json().get("client").get("name").asString()).isEqualTo("Test MCP Client");
        assertThat(details.json().get("client").get("domain").asString()).isEqualTo("localhost");
        assertThat(details.json().get("companyId").asString()).isEqualTo(companyId.toString());
        assertThat(names(details.json().get("requested"))).containsExactly("tinfirm:read", "tinfirm:write",
                "tinfirm:decide");
        assertThat(StreamSupport.stream(details.json().get("offered").spliterator(), false)
            .map(scope -> scope.get("scope").asString())).containsExactly("tinfirm:read", "tinfirm:write",
                    "tinfirm:decide");
        // Another user cannot read it.
        Member stranger = newMember(companyId, "MEMBER");
        assertThat(stranger.session()
            .get("/api/me/oauth/consent?clientId=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8) + "&state="
                    + URLEncoder.encode(consent.get("state"), StandardCharsets.UTF_8))
            .status()).isEqualTo(404);

        // The consent POST needs the CSRF header like every mutating request of the web app.
        Map<String, Object> decision = Map.of("client_id", clientId, "state", consent.get("state"), "scope",
                List.of("tinfirm:read", "tinfirm:write"));
        ApiResponse withoutCsrf = browser.send("POST", "/oauth2/authorize", form(decision),
                Map.of("Content-Type", "application/x-www-form-urlencoded", "X-XSRF-TOKEN", "wrong"));
        assertThat(withoutCsrf.status()).as("%s", withoutCsrf).isEqualTo(403);
        // The person approves read and write, not decide.
        ApiResponse approved = postForm(browser, "/oauth2/authorize", decision);
        assertThat(approved.status()).as("%s", approved).isEqualTo(200);
        String redirect = approved.json().get("redirectUri").asString();
        assertThat(redirect).startsWith(redirectUri + "?");
        Map<String, String> response = query(redirect);
        assertThat(response.get("state")).isEqualTo("client-state");
        assertThat(response.get("iss")).isEqualTo(ISSUER);

        // PKCE: the code needs its verifier.
        ApiResponse wrongVerifier = postForm(client(), "/oauth2/token",
                Map.of("grant_type", "authorization_code", "code", response.get("code"), "redirect_uri", redirectUri,
                        "client_id", clientId, "code_verifier", verifier(), "resource", resource(companyId)));
        assertThat(wrongVerifier.status()).as("%s", wrongVerifier).isEqualTo(400);
        assertThat(wrongVerifier.json().get("error").asString()).isEqualTo("invalid_grant");

        // A fresh flow, exchanged properly.
        Tokens tokens = connect(owner, clientId, redirectUri, List.of("tinfirm:read", "tinfirm:write"), companyId);
        JsonNode claims = claims(tokens.access());
        assertThat(claims.get("sub").asString()).isEqualTo(owner.id().toString());
        assertThat(claims.get("iss").asString()).isEqualTo(ISSUER);
        assertThat(claims.get("aud").toString()).contains(resource(companyId));
        assertThat(claims.get("client_id").asString()).isEqualTo(clientId);
        assertThat(claims.get("scope").toString()).contains("tinfirm:read").contains("tinfirm:write")
            .doesNotContain("tinfirm:decide");
        assertThat(claims.has("jti")).isTrue();
        assertThat(claims.get("exp").asLong() - claims.get("iat").asLong()).isEqualTo(3600);
        assertThat(tokens.scope().split(" ")).containsExactlyInAnyOrder("tinfirm:read", "tinfirm:write");

        // The token works at the company's endpoint: the ping is answered.
        assertThat(mcp(tokens.access(), companyId).status()).isEqualTo(200);
        BearerGrant grant = grantOf(tokens.access(), companyId).orElseThrow();
        assertThat(grant.kind()).isEqualTo(BearerGrant.Kind.OAUTH_GRANT);
        assertThat(grant.scope()).as("the highest scope counts").isEqualTo(ApiScope.WRITE);
        assertThat(grant.id()).isEqualTo(OAuthGrantIds.of(clientId, owner.id(), companyId));
        assertThat(grant.auditDetailKey()).isEqualTo("grantId");

        // Refresh rotates: a new refresh token, and the old one is spent.
        ApiResponse refreshed = postForm(client(), "/oauth2/token", Map.of("grant_type", "refresh_token",
                "refresh_token", tokens.refresh(), "client_id", clientId, "resource", resource(companyId)));
        assertThat(refreshed.status()).as("%s", refreshed).isEqualTo(200);
        String newRefresh = refreshed.json().get("refresh_token").asString();
        String newAccess = refreshed.json().get("access_token").asString();
        assertThat(newRefresh).isNotEqualTo(tokens.refresh());
        assertThat(claims(newAccess).get("aud").toString()).contains(resource(companyId));
        assertThat(mcp(newAccess, companyId).status()).isEqualTo(200);
        ApiResponse reused = postForm(client(), "/oauth2/token", Map.of("grant_type", "refresh_token",
                "refresh_token", tokens.refresh(), "client_id", clientId));
        assertThat(reused.status()).as("%s", reused).isEqualTo(400);
        assertThat(reused.json().get("error").asString()).isEqualTo("invalid_grant");
        // A refresh for another company's resource is refused.
        UUID otherCompany = createCompany(admin(), unique("Other Co"));
        addMember(admin(), otherCompany, owner.email(), "OWNER");
        ApiResponse otherTarget = postForm(client(), "/oauth2/token", Map.of("grant_type", "refresh_token",
                "refresh_token", newRefresh, "client_id", clientId, "resource", resource(otherCompany)));
        assertThat(otherTarget.status()).as("%s", otherTarget).isEqualTo(400);
        assertThat(otherTarget.json().get("error").asString()).isEqualTo("invalid_target");

        // Revocation by the client: the refresh token stops working.
        ApiResponse after = postForm(client(), "/oauth2/token", Map.of("grant_type", "refresh_token",
                "refresh_token", newRefresh, "client_id", clientId));
        assertThat(after.status()).as("refresh before the revocation: %s", after).isIn(200, 400);
        String latestRefresh = after.status() == 200 ? after.json().get("refresh_token").asString() : newRefresh;
        String latestAccess = after.status() == 200 ? after.json().get("access_token").asString() : newAccess;
        ApiResponse revoked = postForm(client(), "/oauth2/revoke",
                Map.of("token", latestRefresh, "token_type_hint", "refresh_token", "client_id", clientId));
        assertThat(revoked.status()).as("%s", revoked).isEqualTo(200);
        ApiResponse afterRevoke = postForm(client(), "/oauth2/token", Map.of("grant_type", "refresh_token",
                "refresh_token", latestRefresh, "client_id", clientId));
        assertThat(afterRevoke.status()).isEqualTo(400);
        assertThat(mcp(latestAccess, companyId).status()).as("the access token goes with its refresh token")
            .isEqualTo(401);

        // Audited: the client's first resolution (instance chain), and the consents (the company's chain).
        JsonNode instance = admin().get("/api/admin/audit-events?limit=200").json().get("items");
        assertThat(events(instance, "oauth.client.resolved")).anySatisfy(event -> {
            assertThat(event.get("actor").get("type").asString()).isEqualTo("SYSTEM");
            assertThat(event.get("targetType").asString()).isEqualTo("oauth_client");
            assertThat(event.get("targetId").asString()).isEqualTo(clientId);
            assertThat(event.get("details").get("host").asString()).isEqualTo("localhost");
        });
        JsonNode company = owner.session().get("/api/companies/" + companyId + "/audit-events?limit=50").json()
            .get("items");
        List<JsonNode> consents = events(company, "oauth.consent.granted");
        assertThat(consents).hasSizeGreaterThanOrEqualTo(2);
        JsonNode consented = consents.getFirst();
        assertThat(consented.get("actor").get("id").asString()).isEqualTo(owner.id().toString());
        assertThat(consented.get("targetType").asString()).isEqualTo("oauth_grant");
        assertThat(consented.get("targetId").asString()).isEqualTo(grant.id().toString());
        assertThat(consented.get("details").get("client").asString()).isEqualTo("localhost");
        assertThat(consented.get("details").get("companyId").asString()).isEqualTo(companyId.toString());
        assertThat(consented.get("details").get("scopes").toString()).contains("tinfirm:read", "tinfirm:write")
            .doesNotContain("decide");
    }

    @Test
    void aTokenIsBoundToItsCompanyAndItsUser() {
        UUID companyA = createCompany(admin(), unique("A Co"));
        UUID companyB = createCompany(admin(), unique("B Co"));
        Member member = newMember(companyA, "MEMBER");
        addMember(admin(), companyB, member.email(), "MEMBER");
        String clientId = publishClient("Audience Client", "http://localhost/callback");
        Tokens tokens = connect(member, clientId, "http://localhost:5000/callback", List.of("tinfirm:read"), companyA);

        assertThat(mcp(tokens.access(), companyA).status()).isEqualTo(200);
        // The member is in company B too, but the token's audience is company A's endpoint.
        ApiResponse atB = mcp(tokens.access(), companyB);
        assertThat(atB.status()).as("%s", atB).isEqualTo(401);
        assertThat(atB.problemCode()).isEqualTo("TOKEN_INVALID");
        assertThat(atB.header("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\", resource_metadata=\""
                + ISSUER + "/.well-known/oauth-protected-resource/api/companies/" + companyB
                + "/mcp\", scope=\"tinfirm:read\"");
        assertThat(grantOf(tokens.access(), companyA).orElseThrow().scope()).isEqualTo(ApiScope.READ);
        assertThat(grantOf(tokens.access(), companyB)).isEmpty();

        // A tampered token is refused.
        String[] parts = tokens.access().split("\\.");
        assertThat(mcp(parts[0] + "." + parts[1] + "." + parts[2].substring(0, parts[2].length() - 4) + "AAAA",
                companyA).status()).isEqualTo(401);

        // A deactivated user's token and refresh are refused.
        assertThat(admin().patch("/api/admin/users/" + member.id(), Map.of("active", false)).status()).isEqualTo(200);
        assertThat(mcp(tokens.access(), companyA).status()).isEqualTo(401);
        ApiResponse refresh = postForm(client(), "/oauth2/token", Map.of("grant_type", "refresh_token",
                "refresh_token", tokens.refresh(), "client_id", clientId));
        assertThat(refresh.status()).as("%s", refresh).isEqualTo(400);
        assertThat(refresh.json().get("error").asString()).isEqualTo("invalid_grant");
    }

    @Test
    void theAuthorizationEndpointChecksTheResourceTheScopesAndTheMembership() {
        UUID companyId = createCompany(admin(), unique("Checks Co"));
        Member member = newMember(companyId, "MEMBER");
        Member outsider = newMember(null, null);
        String clientId = publishClient("Checks Client", "http://localhost/callback");
        String redirectUri = "http://localhost:6000/callback";

        // A resource that is not a company's MCP endpoint: invalid_target, back to the client.
        for (String resource : List.of("https://evil.example/api/companies/" + companyId + "/mcp",
                ISSUER + "/api/companies/" + companyId + "/mcp/extra", ISSUER + "/api/companies/not-a-uuid/mcp",
                ISSUER + "/api/companies/" + companyId.toString().toUpperCase() + "/mcp")) {
            ApiResponse response = member.session()
                .get(authorizePath(clientId, redirectUri, "tinfirm:read", resource, "s1", verifier()));
            assertThat(response.status()).as("%s: %s", resource, response).isEqualTo(302);
            assertThat(query(response.header("Location"))).containsEntry("error", "invalid_target")
                .containsEntry("state", "s1")
                .containsEntry("iss", ISSUER);
        }
        // Without a resource at all, too.
        ApiResponse noResource = member.session()
            .get("/oauth2/authorize?" + form(Map.of("response_type", "code", "client_id", clientId, "redirect_uri",
                    redirectUri, "scope", "tinfirm:read", "state", "s2", "code_challenge", challenge(verifier()),
                    "code_challenge_method", "S256")));
        assertThat(query(noResource.header("Location"))).containsEntry("error", "invalid_target");

        // Unknown and missing scopes.
        ApiResponse unknownScope = member.session()
            .get(authorizePath(clientId, redirectUri, "tinfirm:admin", resource(companyId), "s3", verifier()));
        assertThat(query(unknownScope.header("Location"))).containsEntry("error", "invalid_scope");
        ApiResponse openid = member.session()
            .get(authorizePath(clientId, redirectUri, "openid tinfirm:read", resource(companyId), "s4", verifier()));
        assertThat(query(openid.header("Location"))).containsEntry("error", "invalid_scope");

        // PKCE is required.
        ApiResponse noPkce = member.session()
            .get("/oauth2/authorize?" + form(Map.of("response_type", "code", "client_id", clientId, "redirect_uri",
                    redirectUri, "scope", "tinfirm:read", "state", "s5", "resource", resource(companyId))));
        assertThat(query(noPkce.header("Location"))).containsEntry("error", "invalid_request");

        // A redirect URI the document does not list: no redirect at all.
        ApiResponse foreign = member.session()
            .get(authorizePath(clientId, "https://evil.example/callback", "tinfirm:read", resource(companyId), "s6",
                    verifier()));
        assertThat(foreign.status()).as("%s", foreign).isEqualTo(400);

        // Someone who is not a member: access_denied, naming no company, and no consent page.
        ApiResponse denied = outsider.session()
            .get(authorizePath(clientId, redirectUri, "tinfirm:read", resource(companyId), "s7", verifier()));
        assertThat(denied.status()).as("%s", denied).isEqualTo(302);
        Map<String, String> error = query(denied.header("Location"));
        assertThat(error).containsEntry("error", "access_denied").containsEntry("state", "s7");
        assertThat(String.join(" ", error.values())).doesNotContain(companyId.toString());
        // The same for a company that does not exist.
        ApiResponse noCompany = member.session()
            .get(authorizePath(clientId, redirectUri, "tinfirm:read", resource(UUID.randomUUID()), "s8", verifier()));
        assertThat(query(noCompany.header("Location"))).containsEntry("error", "access_denied");

        // The person denies: no scope approved.
        ApiResponse authorize = member.session()
            .get(authorizePath(clientId, redirectUri, "tinfirm:read", resource(companyId), "s9", verifier()));
        Map<String, String> consent = query(authorize.header("Location"));
        ApiResponse deny = postForm(member.session(), "/oauth2/authorize",
                Map.of("client_id", clientId, "state", consent.get("state")));
        assertThat(deny.status()).as("%s", deny).isEqualTo(200);
        assertThat(query(deny.json().get("redirectUri").asString())).containsEntry("error", "access_denied")
            .containsEntry("state", "s9");
    }

    @Test
    void theConsentNamesTheOwnersAndRefusesAnInstanceAdminWhoIsNoMember() {
        UUID companyId = createCompany(admin(), unique("Notice Co"));
        Member owner = newMember(companyId, "OWNER");
        Member member = newMember(companyId, "MEMBER");
        String clientId = publishClient("Notice Client", "http://localhost/callback");
        String redirectUri = "http://localhost:9300/callback";

        // The member's consent page names the company's OWNERs (security review, row 18).
        Map<String, String> consent = authorize(member, clientId, redirectUri, "tinfirm:read", companyId);
        JsonNode notice = consentDetails(member, clientId, consent.get("state")).get("ownerNotice");
        assertThat(notice.get("companyId").asString()).isEqualTo(companyId.toString());
        assertThat(notice.get("companyName").asString()).startsWith("Notice Co");
        assertThat(names(notice.get("owners"))).contains("User " + owner.email())
            .doesNotContain("User " + member.email());

        // An instance admin who is not a member: access_denied, no consent page, and no break-glass recorded.
        String email = uniqueEmail("instance-admin");
        createUser(admin(), email, true);
        ApiClient instanceAdmin = loginAs(email);
        int accessed = events(owner.session().get("/api/companies/" + companyId + "/audit-events?limit=200").json()
            .get("items"), "admin.company.accessed").size();
        ApiResponse denied = instanceAdmin
            .get(authorizePath(clientId, redirectUri, "tinfirm:read", resource(companyId), "s1", verifier()));
        assertThat(denied.status()).as("%s", denied).isEqualTo(302);
        Map<String, String> error = query(denied.header("Location"));
        assertThat(error).containsEntry("error", "access_denied").containsEntry("state", "s1");
        assertThat(String.join(" ", error.values())).doesNotContain(companyId.toString());
        assertThat(events(owner.session().get("/api/companies/" + companyId + "/audit-events?limit=200").json()
            .get("items"), "admin.company.accessed")).hasSize(accessed);

        // An instance admin who is a member (the creator, an OWNER) consents as a member does.
        ApiResponse asMember = admin()
            .get(authorizePath(clientId, redirectUri, "tinfirm:read", resource(companyId), "s2", verifier()));
        assertThat(asMember.status()).as("%s", asMember).isEqualTo(302);
        assertThat(asMember.header("Location")).startsWith(ISSUER + "/authorize?");
    }

    @Test
    void aConsentIsAskedEveryTimeAndNeverWidenedByAnEarlierOne() {
        UUID companyA = createCompany(admin(), unique("Wide A"));
        UUID companyB = createCompany(admin(), unique("Wide B"));
        Member member = newMember(companyA, "OWNER");
        addMember(admin(), companyB, member.email(), "OWNER");
        String clientId = publishClient("Wide Client", "http://localhost/callback");
        String redirectUri = "http://localhost:7000/callback";

        Tokens decideA = connect(member, clientId, redirectUri,
                List.of("tinfirm:read", "tinfirm:write", "tinfirm:decide"), companyA);
        assertThat(decideA.scope()).contains("tinfirm:decide");
        // Company B: the same client asks for decide; the person approves only read, and gets only read.
        String verifier = verifier();
        String redirect = authorizeAndConsent(member, clientId, redirectUri, "tinfirm:read tinfirm:decide",
                List.of("tinfirm:read"), companyB, verifier);
        Tokens readB = exchange(clientId, redirectUri, query(redirect).get("code"), verifier, companyB);
        assertThat(readB.scope()).isEqualTo("tinfirm:read");
    }

    @Test
    void theConsentOffersEveryLevelAndTheTokenCarriesTheOneChosen() {
        UUID companyId = createCompany(admin(), unique("Levels Co"));
        Member member = newMember(companyId, "OWNER");
        String clientId = publishClient("Levels Client", "http://localhost/callback");
        String redirectUri = "http://localhost:9100/callback";

        // The client asks for reading only, as Claude Code does after the 401: every level is offered.
        Map<String, String> consent = authorize(member, clientId, redirectUri, "tinfirm:read", companyId);
        assertThat(consent.get("scope").split(" ")).containsExactlyInAnyOrder("tinfirm:read", "tinfirm:write",
                "tinfirm:decide");
        JsonNode details = consentDetails(member, clientId, consent.get("state"));
        assertThat(names(details.get("requested"))).containsExactly("tinfirm:read");
        List<JsonNode> offered = StreamSupport.stream(details.get("offered").spliterator(), false).toList();
        assertThat(offered).extracting(scope -> scope.get("scope").asString()).containsExactly("tinfirm:read",
                "tinfirm:write", "tinfirm:decide");
        for (JsonNode scope : offered) {
            ApiScope level = OAuthScopes.of(scope.get("scope").asString()).orElseThrow();
            assertThat(scope.get("title").asString()).isEqualTo(OAuthScopes.title(level));
            assertThat(scope.get("description").asString()).isEqualTo(OAuthScopes.explanation(level));
        }

        // The person chooses write: the token says so, in the token response and in the JWT.
        JsonNode write = consentAndExchange(member, clientId, redirectUri, "tinfirm:read",
                List.of("tinfirm:read", "tinfirm:write"), companyId);
        assertThat(write.get("scope").asString().split(" ")).containsExactlyInAnyOrder("tinfirm:read",
                "tinfirm:write");
        String writeAccess = write.get("access_token").asString();
        assertThat(claims(writeAccess).get("scope").toString()).contains("tinfirm:read", "tinfirm:write")
            .doesNotContain("tinfirm:decide");
        // The MCP endpoint checks a tool's scope against the grant's (McpRequestFilterTest); this test checks
        // the grant the chain resolves the token to.
        assertThat(grantOf(writeAccess, companyId).orElseThrow().scope()).isEqualTo(ApiScope.WRITE);
        assertThat(grantOf(writeAccess, companyId).orElseThrow().allows(ApiScope.DECIDE)).isFalse();

        // Refresh keeps the consented scopes.
        ApiResponse refreshed = postForm(client(), "/oauth2/token", Map.of("grant_type", "refresh_token",
                "refresh_token", write.get("refresh_token").asString(), "client_id", clientId, "resource",
                resource(companyId)));
        assertThat(refreshed.status()).as("%s", refreshed).isEqualTo(200);
        assertThat(refreshed.json().get("scope").asString().split(" ")).containsExactlyInAnyOrder("tinfirm:read",
                "tinfirm:write");
        String refreshedAccess = refreshed.json().get("access_token").asString();
        assertThat(claims(refreshedAccess).get("scope").toString()).contains("tinfirm:write")
            .doesNotContain("tinfirm:decide");
        assertThat(grantOf(refreshedAccess, companyId).orElseThrow().scope()).isEqualTo(ApiScope.WRITE);

        // The person chooses read: read only.
        JsonNode read = consentAndExchange(member, clientId, redirectUri, "tinfirm:read", List.of("tinfirm:read"),
                companyId);
        assertThat(read.get("scope").asString()).isEqualTo("tinfirm:read");
        String readAccess = read.get("access_token").asString();
        assertThat(grantOf(readAccess, companyId).orElseThrow().scope()).isEqualTo(ApiScope.READ);
        assertThat(grantOf(readAccess, companyId).orElseThrow().allows(ApiScope.WRITE)).isFalse();

        // The person chooses decide.
        JsonNode decide = consentAndExchange(member, clientId, redirectUri, "tinfirm:read",
                List.of("tinfirm:read", "tinfirm:write", "tinfirm:decide"), companyId);
        assertThat(decide.get("scope").asString().split(" ")).containsExactlyInAnyOrder("tinfirm:read",
                "tinfirm:write", "tinfirm:decide");
        String decideAccess = decide.get("access_token").asString();
        assertThat(grantOf(decideAccess, companyId).orElseThrow().scope()).isEqualTo(ApiScope.DECIDE);

        // The audit records what the person granted, not what the client asked for.
        JsonNode events = member.session().get("/api/companies/" + companyId + "/audit-events?limit=50").json()
            .get("items");
        List<String> granted = events(events, "oauth.consent.granted").stream()
            .map(event -> event.get("details").get("scopes").toString())
            .toList();
        assertThat(granted).hasSize(3);
        assertThat(granted).anySatisfy(scopes -> assertThat(scopes).contains("tinfirm:write")
            .doesNotContain("tinfirm:decide"));
        assertThat(granted).anySatisfy(scopes -> assertThat(scopes).contains("tinfirm:read")
            .doesNotContain("tinfirm:write"));
        assertThat(granted).anySatisfy(scopes -> assertThat(scopes).contains("tinfirm:decide"));
    }

    @Test
    void theRequestedLevelIsPreselectedAndAConsentStaysWithinTheThree() {
        UUID companyId = createCompany(admin(), unique("Preselect Co"));
        Member member = newMember(companyId, "MEMBER");
        String clientId = publishClient("Preselect Client", "http://localhost/callback");
        String redirectUri = "http://localhost:9200/callback";

        // A client that asks for write: requested is write, offered is still the three.
        Map<String, String> consent = authorize(member, clientId, redirectUri, "tinfirm:write", companyId);
        JsonNode details = consentDetails(member, clientId, consent.get("state"));
        assertThat(names(details.get("requested"))).containsExactly("tinfirm:write");
        assertThat(StreamSupport.stream(details.get("offered").spliterator(), false)
            .map(scope -> scope.get("scope").asString())).containsExactly("tinfirm:read", "tinfirm:write",
                    "tinfirm:decide");

        // A consent that posts a scope outside the three is refused, and no code is issued.
        for (String outside : List.of("openid", "tinfirm:admin")) {
            Map<String, String> pending = authorize(member, clientId, redirectUri, "tinfirm:read", companyId);
            ApiResponse refused = postForm(member.session(), "/oauth2/authorize", Map.of("client_id", clientId,
                    "state", pending.get("state"), "scope", List.of("tinfirm:read", outside)));
            assertThat(refused.status()).as("%s: %s", outside, refused).isEqualTo(200);
            Map<String, String> answer = query(refused.json().get("redirectUri").asString());
            assertThat(answer).as(outside).containsEntry("error", "invalid_scope").doesNotContainKey("code");
        }

        // A request without any of the three stays refused; a foreign scope next to one of them too.
        for (String requested : List.of("tinfirm:admin", "openid tinfirm:read", "tinfirm:read profile")) {
            ApiResponse response = member.session()
                .get(authorizePath(clientId, redirectUri, requested, resource(companyId), "s", verifier()));
            assertThat(response.status()).as("%s: %s", requested, response).isEqualTo(302);
            assertThat(query(response.header("Location"))).as(requested).containsEntry("error", "invalid_scope");
        }
    }

    @Test
    void connectedAppsListsAndRevokesGrantsWithTheirAudit() {
        UUID companyId = createCompany(admin(), unique("Apps Co"));
        Member member = newMember(companyId, "OWNER");
        Member other = newMember(companyId, "ADMIN");
        String clientId = publishClient("Listed Client", "http://localhost/callback");
        Tokens tokens = connect(member, clientId, "http://localhost:8123/callback",
                List.of("tinfirm:read", "tinfirm:decide"), companyId);
        // A second authorization of the same grant: still one grant.
        connect(member, clientId, "http://localhost:8124/callback", List.of("tinfirm:read", "tinfirm:decide"),
                companyId);

        ApiResponse listed = member.session().get("/api/me/oauth/grants");
        assertThat(listed.status()).as("%s", listed).isEqualTo(200);
        assertThat(listed.json()).hasSize(1);
        JsonNode grant = listed.json().get(0);
        UUID grantId = OAuthGrantIds.of(clientId, member.id(), companyId);
        assertThat(grant.get("id").asString()).isEqualTo(grantId.toString());
        assertThat(grant.get("client").get("name").asString()).isEqualTo("Listed Client");
        assertThat(grant.get("client").get("domain").asString()).isEqualTo("localhost");
        assertThat(grant.get("companyId").asString()).isEqualTo(companyId.toString());
        assertThat(grant.get("companyName").asString()).startsWith("Apps Co");
        assertThat(grant.get("scopes").toString()).contains("tinfirm:read", "tinfirm:decide");
        assertThat(grant.get("grantedAt").isString()).isTrue();
        assertThat(grant.get("lastUsedAt").isString()).isTrue();
        assertThat(other.session().get("/api/me/oauth/grants").json()).isEmpty();

        // Another user cannot revoke it (and learns nothing: 204 either way).
        assertThat(other.session().delete("/api/me/oauth/grants/" + grantId).status()).isEqualTo(204);
        assertThat(mcp(tokens.access(), companyId).status()).isEqualTo(200);

        assertThat(member.session().delete("/api/me/oauth/grants/" + grantId).status()).isEqualTo(204);
        assertThat(member.session().get("/api/me/oauth/grants").json()).isEmpty();
        // The tokens stop working at once, the access token too.
        assertThat(mcp(tokens.access(), companyId).status()).isEqualTo(401);
        ApiResponse refresh = postForm(client(), "/oauth2/token", Map.of("grant_type", "refresh_token",
                "refresh_token", tokens.refresh(), "client_id", clientId));
        assertThat(refresh.status()).isEqualTo(400);
        // Idempotent.
        assertThat(member.session().delete("/api/me/oauth/grants/" + grantId).status()).isEqualTo(204);
        assertThat(member.session().delete("/api/me/oauth/grants/" + UUID.randomUUID()).status()).isEqualTo(204);

        JsonNode events = member.session().get("/api/companies/" + companyId + "/audit-events?limit=50").json()
            .get("items");
        List<JsonNode> revoked = events(events, "oauth.grant.revoked");
        assertThat(revoked).hasSize(1);
        assertThat(revoked.getFirst().get("actor").get("id").asString()).isEqualTo(member.id().toString());
        assertThat(revoked.getFirst().get("targetId").asString()).isEqualTo(grantId.toString());
        assertThat(revoked.getFirst().get("details").get("client").asString()).isEqualTo("localhost");
        assertThat(events(events, "oauth.consent.granted")).hasSize(2);
    }

    @Test
    void revokingAGrantRemovesEveryAuthorizationBeyondTheListsCap() throws Exception {
        UUID companyId = createCompany(admin(), unique("Many Co"));
        Member member = newMember(companyId, "OWNER");
        String clientId = publishClient("Busy Client", "http://localhost/callback");
        Tokens tokens = connect(member, clientId, "http://localhost:8125/callback", List.of("tinfirm:read"),
                companyId);
        // More copies of the authorization than "Connected apps" reads, all ordered before the real one, so a
        // revocation that removed only the page it listed would leave the real one and its tokens.
        int copies = OAuthGrantService.MAX_AUTHORIZATIONS + 20;
        try (var owner = TestDatabase.connectAsOwner();
                var insert = owner.prepareStatement("""
                        INSERT INTO oauth2_authorization
                        SELECT (jsonb_populate_record(a,
                                    jsonb_build_object('id', '0000-copy-' || lpad(g::text, 4, '0')))).*
                          FROM oauth2_authorization a, generate_series(1, ?) g
                         WHERE a.principal_name = ?""")) {
            insert.setInt(1, copies);
            insert.setString(2, member.id().toString());
            assertThat(insert.executeUpdate()).isEqualTo(copies);
        }
        assertThat(authorizationsOf(member.id())).isEqualTo(copies + 1);

        UUID grantId = OAuthGrantIds.of(clientId, member.id(), companyId);
        assertThat(member.session().delete("/api/me/oauth/grants/" + grantId).status()).isEqualTo(204);

        assertThat(authorizationsOf(member.id())).isZero();
        assertThat(mcp(tokens.access(), companyId).status()).isEqualTo(401);
        assertThat(member.session().get("/api/me/oauth/grants").json()).isEmpty();
    }

    private static int authorizationsOf(UUID userId) throws Exception {
        try (var owner = TestDatabase.connectAsOwner();
                var count = owner
                    .prepareStatement("SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?")) {
            count.setString(1, userId.toString());
            try (var rows = count.executeQuery()) {
                rows.next();
                return rows.getInt(1);
            }
        }
    }

    @Test
    void aDocumentWithTheMostRedirectUrisItMayNameIsStored() {
        UUID companyId = createCompany(admin(), unique("Long Co"));
        Member member = newMember(companyId, "OWNER");
        // 10 redirect URIs of 200 characters, the most a document may name.
        String[] redirectUris = new String[10];
        for (int i = 0; i < redirectUris.length; i++) {
            String prefix = "http://localhost/callback-" + i + "/";
            redirectUris[i] = prefix + "x".repeat(200 - prefix.length());
        }
        assertThat(redirectUris[9]).hasSize(200);
        String clientId = publishClient("Long Client", redirectUris);

        ApiResponse authorize = member.session().get(authorizePath(clientId, redirectUris[9], "tinfirm:read",
                resource(companyId), "s", verifier()));
        assertThat(authorize.status()).as("%s", authorize).isEqualTo(302);
        assertThat(authorize.header("Location")).startsWith(ISSUER + "/authorize?");
    }

    @Test
    void theMcpInspectorIsABuiltInClient() {
        UUID companyId = createCompany(admin(), unique("Inspector Co"));
        Member member = newMember(companyId, "OWNER");
        Tokens tokens = connect(member, BuiltInOAuthClients.MCP_INSPECTOR, "http://localhost:6274/oauth/callback",
                List.of("tinfirm:read"), companyId);
        assertThat(mcp(tokens.access(), companyId).status()).isEqualTo(200);
        JsonNode grant = member.session().get("/api/me/oauth/grants").json().get(0);
        assertThat(grant.get("client").get("name").asString()).isEqualTo("MCP Inspector");
        assertThat(grant.get("client").has("domain")).isFalse();

        // Loopback only, on its callback path; any port.
        ApiResponse otherPath = member.session()
            .get(authorizePath(BuiltInOAuthClients.MCP_INSPECTOR, "http://localhost:6274/elsewhere", "tinfirm:read",
                    resource(companyId), "s", verifier()));
        assertThat(otherPath.status()).isEqualTo(400);
        ApiResponse otherHost = member.session()
            .get(authorizePath(BuiltInOAuthClients.MCP_INSPECTOR, "https://inspector.example/oauth/callback",
                    "tinfirm:read", resource(companyId), "s", verifier()));
        assertThat(otherHost.status()).isEqualTo(400);
    }

    @Test
    void theOutlookAddInIsABuiltInClient() {
        UUID companyId = createCompany(admin(), unique("Outlook Co"));
        Member member = newMember(companyId, "MEMBER");
        String callback = ISSUER + BuiltInOAuthClients.OUTLOOK_CALLBACK;
        Tokens tokens = connect(member, BuiltInOAuthClients.OUTLOOK, callback, List.of("tinfirm:write"), companyId);
        assertThat(tokens.scope().split(" ")).contains("tinfirm:write");
        // Its task pane calls the endpoint from Tinfirm's own origin (ADR-0074).
        ApiResponse fromTaskPane = client().send("POST", "/api/companies/" + companyId + "/mcp",
                Map.of("jsonrpc", "2.0", "id", 1, "method", "ping"),
                Map.of("Authorization", "Bearer " + tokens.access(), "Origin", ISSUER));
        assertThat(fromTaskPane.status()).as("%s", fromTaskPane).isEqualTo(200);
        JsonNode grant = member.session().get("/api/me/oauth/grants").json().get(0);
        assertThat(grant.get("client").get("name").asString()).isEqualTo("Tinfirm for Outlook");
        assertThat(grant.get("client").has("domain")).isFalse();

        // Its callback at the public URL only.
        ApiResponse otherPath = member.session()
            .get(authorizePath(BuiltInOAuthClients.OUTLOOK, ISSUER + "/outlook/elsewhere", "tinfirm:write",
                    resource(companyId), "s", verifier()));
        assertThat(otherPath.status()).isEqualTo(400);
        ApiResponse otherHost = member.session()
            .get(authorizePath(BuiltInOAuthClients.OUTLOOK, "https://addin.example/outlook/callback", "tinfirm:write",
                    resource(companyId), "s", verifier()));
        assertThat(otherHost.status()).isEqualTo(400);
    }

    @Test
    void metadataDocumentsGoThroughTheOutboundGuard() {
        UUID companyId = createCompany(admin(), unique("Guard Co"));
        Member member = newMember(companyId, "OWNER");
        String allowed = publishClient("Guarded", "http://localhost/callback");
        // The same document, but at a loopback IP literal: the guard refuses it before any request.
        String atLoopback = allowed.replace("http://localhost:", "http://127.0.0.1:");
        String atPrivate = "https://10.0.0.1/client.json";
        String atLinkLocal = "https://169.254.169.254/latest/meta-data";
        for (String clientId : List.of(atLoopback, atPrivate, atLinkLocal)) {
            ApiResponse response = member.session()
                .get(authorizePath(clientId, "http://localhost:9/callback", "tinfirm:read", resource(companyId), "s",
                        verifier()));
            assertThat(response.status()).as("%s: %s", clientId, response).isEqualTo(400);
            assertThat(response.json().get("error").asString()).isEqualTo("invalid_request");
        }
        // A document whose client_id is not its URL, and a redirect: refused.
        String path = "/client-" + UUID.randomUUID() + ".json";
        CIMD.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"client_id\":\"https://someone-else.example/client\",\"redirect_uris\":[\"http://localhost/cb\"]}")));
        String moved = "/moved-" + UUID.randomUUID() + ".json";
        CIMD.stubFor(get(urlEqualTo(moved))
            .willReturn(aResponse().withStatus(302).withHeader("Location", allowed)));
        for (String clientId : List.of("http://localhost:" + CIMD.port() + path,
                "http://localhost:" + CIMD.port() + moved)) {
            ApiResponse response = member.session()
                .get(authorizePath(clientId, "http://localhost:9/cb", "tinfirm:read", resource(companyId), "s",
                        verifier()));
            assertThat(response.status()).as("%s: %s", clientId, response).isEqualTo(400);
        }
    }

    @Test
    void discoveryDocumentsAndUnservedEndpoints() {
        UUID companyId = createCompany(admin(), unique("Meta Co"));

        ApiResponse resourceMetadata = client()
            .get("/.well-known/oauth-protected-resource/api/companies/" + companyId + "/mcp");
        assertThat(resourceMetadata.status()).as("%s", resourceMetadata).isEqualTo(200);
        JsonNode metadata = resourceMetadata.json();
        assertThat(metadata.get("resource").asString()).isEqualTo(resource(companyId));
        assertThat(metadata.get("authorization_servers").toString()).isEqualTo("[\"" + ISSUER + "\"]");
        assertThat(metadata.get("scopes_supported").toString()).isEqualTo("[\"tinfirm:read\"]");
        assertThat(metadata.get("bearer_methods_supported").toString()).isEqualTo("[\"header\"]");
        // Public: it names no company, and answers alike for an id that is no company's (RFC 9728 makes
        // resource_name optional), so that it never tells a stranger whether a company exists.
        assertThat(metadata.has("resource_name")).isFalse();
        assertThat(resourceMetadata.body()).doesNotContain("Meta Co");
        UUID unknown = UUID.randomUUID();
        ApiResponse unknownMetadata = client()
            .get("/.well-known/oauth-protected-resource/api/companies/" + unknown + "/mcp");
        assertThat(unknownMetadata.status()).as("%s", unknownMetadata).isEqualTo(200);
        assertThat(unknownMetadata.json().get("resource").asString()).isEqualTo(resource(unknown));
        assertThat(unknownMetadata.body().replace(unknown.toString(), "ID"))
            .isEqualTo(resourceMetadata.body().replace(companyId.toString(), "ID"));
        assertThat(client().get("/.well-known/oauth-protected-resource/api/companies/nope/mcp").status())
            .isEqualTo(404);

        // The 401 of the endpoint points there.
        ApiResponse unauthorized = client().send("POST", "/api/companies/" + companyId + "/mcp", Map.of(), Map.of());
        assertThat(unauthorized.status()).isEqualTo(401);
        assertThat(unauthorized.header("WWW-Authenticate")).isEqualTo("Bearer resource_metadata=\"" + ISSUER
                + "/.well-known/oauth-protected-resource/api/companies/" + companyId + "/mcp\", scope=\"tinfirm:read\"");

        ApiResponse server = client().get("/.well-known/oauth-authorization-server");
        assertThat(server.status()).as("%s", server).isEqualTo(200);
        JsonNode as = server.json();
        assertThat(as.get("issuer").asString()).isEqualTo(ISSUER);
        assertThat(as.get("authorization_endpoint").asString()).isEqualTo(ISSUER + "/oauth2/authorize");
        assertThat(as.get("token_endpoint").asString()).isEqualTo(ISSUER + "/oauth2/token");
        assertThat(as.get("revocation_endpoint").asString()).isEqualTo(ISSUER + "/oauth2/revoke");
        assertThat(as.get("jwks_uri").asString()).isEqualTo(ISSUER + "/oauth2/jwks");
        assertThat(as.get("scopes_supported").toString())
            .isEqualTo("[\"tinfirm:read\",\"tinfirm:write\",\"tinfirm:decide\"]");
        assertThat(as.get("code_challenge_methods_supported").toString()).isEqualTo("[\"S256\"]");
        assertThat(as.get("grant_types_supported").toString())
            .isEqualTo("[\"authorization_code\",\"refresh_token\"]");
        assertThat(as.get("token_endpoint_auth_methods_supported").toString()).isEqualTo("[\"none\"]");
        assertThat(as.get("client_id_metadata_document_supported").asBoolean()).isTrue();
        assertThat(as.get("authorization_response_iss_parameter_supported").asBoolean()).isTrue();
        assertThat(as.has("registration_endpoint")).isFalse();
        assertThat(as.has("introspection_endpoint")).isFalse();

        JsonNode keys = client().get("/oauth2/jwks").json().get("keys");
        assertThat(keys).hasSize(1);
        assertThat(keys.get(0).get("kty").asString()).isEqualTo("RSA");
        assertThat(keys.get(0).has("kid")).isTrue();
        assertThat(keys.get(0).has("d")).as("never the private key").isFalse();

        // Open dynamic registration is off, for anyone.
        Member member = newMember(companyId, "OWNER");
        for (ApiClient caller : List.of(client(), member.session())) {
            assertThat(caller.post("/oauth2/register", Map.of("redirect_uris", List.of("http://localhost/cb")))
                .status()).isEqualTo(404);
            assertThat(caller.get("/oauth2/register").status()).isEqualTo(404);
        }
        assertThat(client().get("/.well-known/openid-configuration").status()).isEqualTo(404);
    }

    @Test
    void theSigningKeyIsAnInstanceKeyOfTheVaultThatTheRewrapCovers() throws Exception {
        // A token makes sure the key exists.
        UUID companyId = createCompany(admin(), unique("Key Co"));
        connect(newMember(companyId, "OWNER"), BuiltInOAuthClients.MCP_INSPECTOR,
                "http://localhost:6274/oauth/callback", List.of("tinfirm:read"), companyId);
        try (var app = TestDatabase.connectAsApp();
                var select = app.prepareStatement(
                        "SELECT kid, algorithm, key_id, octet_length(ciphertext) FROM instance_key WHERE name = 'oauth-signing'");
                var rows = select.executeQuery()) {
            assertThat(rows.next()).isTrue();
            String kid = rows.getString(1);
            assertThat(rows.getString(2)).isEqualTo("RSA");
            assertThat(rows.getInt(4)).isGreaterThan(1000);
            assertThat(client().get("/oauth2/jwks").json().get("keys").get(0).get("kid").asString()).isEqualTo(kid);
        }
        ApiResponse rewrap = admin().post("/api/admin/vault/rewrap", Map.of());
        assertThat(rewrap.status()).as("%s", rewrap).isEqualTo(200);
        // Created once per instance, audited as a secret of the instance (the shared test database holds the one;
        // other instance keys, such as the sign-ins' PKCE key, are audited apart).
        try (var superuser = TestDatabase.connectAsSuperuser(TestDatabase.jdbcUrl());
                var select = superuser.prepareStatement("""
                        SELECT actor_type, details->>'name', company_id FROM audit_event
                         WHERE action = 'secret.created' AND target_type = 'instance_key'
                           AND details->>'name' = 'oauth-signing'""");
                var rows = select.executeQuery()) {
            assertThat(rows.next()).isTrue();
            assertThat(rows.getString(1)).isEqualTo("SYSTEM");
            assertThat(rows.getString(2)).isEqualTo("oauth-signing");
            assertThat(rows.getObject(3)).as("the instance chain").isNull();
            assertThat(rows.next()).isFalse();
        }
    }
}
