package com.flowtivo.tinfirm.system.internal.oauth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.http.OutboundProperties;
import com.flowtivo.tinfirm.system.internal.http.PersonalStubHosts;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The endpoints an MCP server's authorization server names share its issuer's origin (the security review of
 * 2026-10-27, M8): a server that names a real provider's authorization endpoint and its own token endpoint would
 * otherwise receive the codes and PKCE verifiers of that provider's consent. An origin the operator allows for a
 * split-hosted provider passes. The stubs are two servers on {@code localhost}, two origins by their ports.
 */
class OAuthDiscoveryTest {

    private HttpServer issuerHost;

    private HttpServer otherHost;

    /** What the issuer's metadata names; each test changes it. */
    private Map<String, Object> metadata;

    private OutboundHttp http() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new OutboundHttp(new OutboundProperties(true, false, Duration.ofSeconds(5), Duration.ofSeconds(10),
                Set.of("localhost"), Set.of()), environment,
                new DefaultListableBeanFactory().getBeanProvider(PersonalStubHosts.class));
    }

    @BeforeEach
    void start() throws IOException {
        issuerHost = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        otherHost = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        issuerHost.createContext("/", this::issuerAnswers);
        otherHost.createContext("/", exchange -> answer(exchange, 404, "{}"));
        issuerHost.start();
        otherHost.start();
        metadata = new LinkedHashMap<>();
        metadata.put("issuer", issuer());
        metadata.put("authorization_endpoint", issuer() + "/authorize");
        metadata.put("token_endpoint", issuer() + "/token");
        metadata.put("revocation_endpoint", issuer() + "/revoke");
        metadata.put("registration_endpoint", issuer() + "/register");
        metadata.put("code_challenge_methods_supported", List.of("S256"));
    }

    @AfterEach
    void stop() {
        issuerHost.stop(0);
        otherHost.stop(0);
    }

    private String base() {
        return "http://localhost:" + issuerHost.getAddress().getPort();
    }

    private String issuer() {
        return base() + "/as";
    }

    private String other() {
        return "http://localhost:" + otherHost.getAddress().getPort();
    }

    private String endpoint() {
        return base() + "/mcp";
    }

    private void issuerAnswers(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        if (path.equals("/mcp")) {
            exchange.getResponseHeaders().add("WWW-Authenticate", "Bearer resource_metadata=\"" + base() + "/prm\"");
            answer(exchange, 401, "{}");
        }
        else if (path.equals("/prm")) {
            answer(exchange, 200, Json.write(Map.of("resource", endpoint(), "authorization_servers",
                    List.of(issuer()))));
        }
        else if (path.equals("/.well-known/oauth-authorization-server/as")) {
            answer(exchange, 200, Json.write(metadata));
        }
        else {
            answer(exchange, 404, "{}");
        }
    }

    private static void answer(HttpExchange exchange, int status, String body) throws IOException {
        try (exchange) {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
        }
    }

    @Test
    void endpointsOnTheIssuersOriginAreTaken() {
        OAuthDiscovery.Outcome outcome = new OAuthDiscovery(http()).discover(endpoint());

        assertThat(outcome.error()).isNull();
        assertThat(outcome.discovered()).isNotNull();
        assertThat(outcome.discovered().tokenEndpoint()).isEqualTo(issuer() + "/token");
    }

    @Test
    void anyEndpointOffTheIssuersOriginIsRefused() {
        for (String field : List.of("authorization_endpoint", "token_endpoint", "revocation_endpoint",
                "registration_endpoint")) {
            Object before = metadata.put(field, other() + "/" + field);

            OAuthDiscovery.Outcome outcome = new OAuthDiscovery(http()).discover(endpoint());

            assertThat(outcome.discovered()).as(field).isNull();
            assertThat(outcome.error()).as(field)
                .startsWith("The " + field + " of " + issuer() + " is not allowed: it is on http://localhost:"
                        + otherHost.getAddress().getPort() + ", not on the issuer's origin");
            metadata.put(field, before);
        }
    }

    @Test
    void anOriginTheOperatorAllowsIsTaken() {
        metadata.put("token_endpoint", other() + "/token");
        metadata.put("revocation_endpoint", other() + "/revoke");
        OAuthDiscoveryProperties allowed = new OAuthDiscoveryProperties(List.of(" " + other() + "/ ", ""));

        OAuthDiscovery.Outcome outcome = new OAuthDiscovery(http(), allowed.origins()).discover(endpoint());

        assertThat(outcome.error()).isNull();
        assertThat(outcome.discovered().tokenEndpoint()).isEqualTo(other() + "/token");
        // The list names that origin only: another port is still refused.
        metadata.put("revocation_endpoint", "http://localhost:" + (otherHost.getAddress().getPort() + 1) + "/r");
        assertThat(new OAuthDiscovery(http(), allowed.origins()).discover(endpoint()).error())
            .contains("not on the issuer's origin");
    }

    @Test
    void originsAreSchemeHostAndPort() {
        assertThat(OAuthDiscovery.origin("HTTPS://Auth.Example.COM/oauth/token"))
            .isEqualTo("https://auth.example.com:443");
        assertThat(OAuthDiscovery.origin("https://auth.example.com:443")).isEqualTo("https://auth.example.com:443");
        assertThat(OAuthDiscovery.origin("https://auth.example.com.:8443/x")).isEqualTo("https://auth.example.com:8443");
        assertThat(OAuthDiscovery.origin("http://auth.example.com")).isEqualTo("http://auth.example.com:80");
        assertThat(OAuthDiscovery.origin("/relative")).isNull();
        assertThat(OAuthDiscovery.origin("not a url")).isNull();
    }

    @Test
    void theOperatorsListNamesOriginsOnly() {
        assertThat(new OAuthDiscoveryProperties(List.of("https://oauth2.googleapis.com")).origins())
            .containsExactly("https://oauth2.googleapis.com:443");
        for (String entry : List.of("https://oauth2.googleapis.com/token", "oauth2.googleapis.com",
                "https://user@oauth2.googleapis.com", "https://oauth2.googleapis.com?x=1")) {
            assertThatThrownBy(() -> new OAuthDiscoveryProperties(List.of(entry))).as(entry)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("TINFIRM_OAUTH_DISCOVERY_ALLOWEDENDPOINTORIGINS");
        }
    }
}
