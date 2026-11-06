package com.flowtivo.tinfirm.support;

import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import tools.jackson.databind.json.JsonMapper;

/**
 * An MCP server on the {@link McpStub} that takes only OAuth (the package 1 contract, §3.3): a request to its endpoint
 * without an {@code Authorization} header is answered {@code 401} with the link to its protected-resource metadata
 * (RFC 9728), which names an authorization server of its own on the same stub (RFC 8414: an authorization, a token, a
 * revocation and, when asked, a registration endpoint, S256, the scopes {@code crm:read crm:write}). The server serves
 * its tools to the access tokens {@link #serves} names, as {@code Authorization: Bearer}. TEST FIXTURES ONLY: every
 * client id, code and token here is a test value.
 */
public final class McpAuthorizationServer {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final McpStub.Instance mcp;

    private final String path;

    private final boolean cimd;

    private final boolean registration;

    private McpAuthorizationServer(McpStub.Instance mcp, String path, boolean cimd, boolean registration) {
        this.mcp = mcp;
        this.path = path;
        this.cimd = cimd;
        this.registration = registration;
    }

    /**
     * Stubs the MCP server's endpoint and its authorization server.
     *
     * @param cimd         whether the authorization server takes Tinfirm's client metadata document
     * @param registration whether it has a registration endpoint (RFC 7591), which issues {@code test-dynamic-client}
     */
    public static McpAuthorizationServer of(McpStub.Instance mcp, boolean cimd, boolean registration) {
        WireMockServer server = McpStub.server();
        String endpointPath = URI.create(mcp.url()).getPath();
        McpAuthorizationServer as = new McpAuthorizationServer(mcp, endpointPath + "-as", cimd, registration);
        server.stubFor(post(urlEqualTo(endpointPath)).atPriority(3).withHeader("Authorization", absent())
            .willReturn(aResponse().withStatus(401)
                .withHeader("WWW-Authenticate", "Bearer resource_metadata=\"" + mcp.url() + "-resource\"")));
        server.stubFor(get(urlEqualTo(endpointPath + "-resource")).willReturn(json(200, Map.of("resource", mcp.url(),
                "authorization_servers", List.of(as.issuer())))));
        server.stubFor(get(urlEqualTo("/.well-known/oauth-authorization-server" + as.path))
            .willReturn(json(200, as.metadata(cimd, registration))));
        if (registration) {
            server.stubFor(post(urlEqualTo(as.path + "/register")).willReturn(json(201, Map.of("client_id",
                    "test-dynamic-client", "client_name", "Tinfirm", "token_endpoint_auth_method", "none"))));
        }
        server.stubFor(post(urlEqualTo(as.path + "/revoke")).willReturn(aResponse().withStatus(200)));
        return as;
    }

    /** Its protected-resource metadata answers only after the delay: a discovery that is still running. */
    public McpAuthorizationServer slowDiscovery(int millis) {
        McpStub.server().stubFor(get(urlEqualTo(URI.create(mcp.url()).getPath() + "-resource")).atPriority(1)
            .willReturn(json(200, Map.of("resource", mcp.url(), "authorization_servers", List.of(issuer())))
                .withFixedDelay(millis)));
        return this;
    }

    public String issuer() {
        return "http://localhost:" + McpStub.server().port() + path;
    }

    public String url(String suffix) {
        return issuer() + suffix;
    }

    /**
     * Its metadata names these endpoints instead of its own, the others as before: a field that maps to null is left
     * out, one that names a suffix is on its issuer's origin, one that names a URL is that URL.
     */
    public McpAuthorizationServer metadataNames(Map<String, @Nullable String> endpoints) {
        Map<String, Object> metadata = metadata(cimd, registration);
        endpoints.forEach((field, value) -> {
            if (value == null) {
                metadata.remove(field);
            }
            else {
                metadata.put(field, value.startsWith("/") ? url(value) : value);
            }
        });
        McpStub.server().stubFor(get(urlEqualTo("/.well-known/oauth-authorization-server" + path)).atPriority(1)
            .willReturn(json(200, metadata)));
        return this;
    }

    /** The registration endpoint issues this client, before the one {@link #of} stubbed. */
    public McpAuthorizationServer registers(String clientId, String clientSecret) {
        McpStub.server().stubFor(post(urlEqualTo(path + "/register")).atPriority(1).willReturn(json(201, Map.of(
                "client_id", clientId, "client_secret", clientSecret, "client_name", "Tinfirm",
                "token_endpoint_auth_method", "client_secret_basic"))));
        return this;
    }

    /** The MCP server serves these tools to requests with {@code Authorization: Bearer <accessToken>}. */
    public McpAuthorizationServer serves(String accessToken, List<Map<String, Object>> tools) {
        mcp.requiring("Authorization", "Bearer " + accessToken).serves(tools);
        return this;
    }

    /**
     * The token endpoint answers a request whose form contains {@code match} (a code, a refresh token) with these
     * tokens; a later stub of the same match wins.
     *
     * @param refreshToken null: the answer names none (the old one stays)
     */
    public McpAuthorizationServer tokens(String match, String accessToken, @Nullable String refreshToken,
            int expiresIn) {
        Map<String, Object> tokens = new LinkedHashMap<>();
        tokens.put("access_token", accessToken);
        if (refreshToken != null) {
            tokens.put("refresh_token", refreshToken);
        }
        tokens.put("token_type", "Bearer");
        tokens.put("expires_in", expiresIn);
        tokens.put("scope", "crm:read crm:write");
        McpStub.server().stubFor(post(urlEqualTo(path + "/token")).withRequestBody(containing(match))
            .willReturn(json(200, tokens)));
        return this;
    }

    /** The token endpoint refuses a request whose form contains {@code match}, before any other stub of it. */
    public McpAuthorizationServer refuses(String match, String error) {
        McpStub.server().stubFor(post(urlEqualTo(path + "/token")).atPriority(1).withRequestBody(containing(match))
            .willReturn(json(400, Map.of("error", error, "error_description", "test-detail-of-refusal"))));
        return this;
    }

    /**
     * Signs an account in: the sign-in on the account's path, then the callback with a code that the token endpoint
     * answers with these tokens.
     *
     * @param accountPath {@code /api/companies/{id}/me/accounts/{systemId}}, or a credential of the system
     */
    public McpAuthorizationServer signIn(ApiClient person, String accountPath, String accessToken,
            String refreshToken, int expiresIn) {
        return complete(person, OAuthStubs.startSignIn(person, accountPath, "/accounts"), accessToken, refreshToken,
                expiresIn);
    }

    /**
     * Signs a new credential of the system in (ADR-0072), named.
     *
     * @param credentialsPath the system's credentials, {@code /api/companies/{companyId}/systems/{systemId}/credentials}
     */
    public McpAuthorizationServer signInCredential(ApiClient admin, String credentialsPath, String name,
            String accessToken, String refreshToken, int expiresIn) {
        return complete(admin, OAuthStubs.startSignIn(admin, credentialsPath, Map.of("name", name, "returnTo",
                "/accounts")), accessToken, refreshToken, expiresIn);
    }

    private McpAuthorizationServer complete(ApiClient person, String state, String accessToken, String refreshToken,
            int expiresIn) {
        String code = "test-mcp-code-" + UUID.randomUUID();
        tokens("code=" + code, accessToken, refreshToken, expiresIn);
        ApiResponse callback = person.get("/api/oauth/callback?code=" + code + "&state=" + state);
        assertThat(callback.status()).as("callback: %s", callback).isEqualTo(303);
        assertThat(callback.header("Location")).as("callback").startsWith("/accounts?account=connected");
        return this;
    }

    /** The requests the authorization server took at the endpoint, oldest first. */
    public List<LoggedRequest> requests(String suffix) {
        return McpStub.server().findAll(postRequestedFor(urlEqualTo(path + suffix)));
    }

    /** The forms of the refresh requests the token endpoint took, oldest first. */
    public List<Map<String, String>> refreshes() {
        return requests("/token").stream()
            .map(OAuthStubs::form)
            .filter(form -> "refresh_token".equals(form.get("grant_type")))
            .toList();
    }

    private Map<String, Object> metadata(boolean cimd, boolean registration) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer());
        metadata.put("authorization_endpoint", url("/authorize"));
        metadata.put("token_endpoint", url("/token"));
        metadata.put("revocation_endpoint", url("/revoke"));
        if (registration) {
            metadata.put("registration_endpoint", url("/register"));
        }
        metadata.put("scopes_supported", List.of("crm:read", "crm:write"));
        metadata.put("code_challenge_methods_supported", List.of("S256"));
        metadata.put("client_id_metadata_document_supported", cimd);
        return metadata;
    }

    private static ResponseDefinitionBuilder json(int status, Object body) {
        return aResponse().withStatus(status).withHeader("Content-Type", "application/json")
            .withBody(JSON.writeValueAsString(body));
    }
}
