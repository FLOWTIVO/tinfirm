package com.flowtivo.tinfirm.identity.internal.saml;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;

import com.flowtivo.tinfirm.support.SamlTestKeys;

/**
 * TEST FIXTURE ONLY: Keycloak as a SAML identity provider (ADR-0086, decision 16), the release {@code make up-oidc}
 * runs, with a realm made for the test: one SAML client that signs the response and the assertion, and one that also
 * encrypts the assertion for the service provider's key. Its people sign in with a password on Keycloak's own form,
 * which {@link #login} fills without a browser. Every password and key here is a test fixture.
 */
final class KeycloakSaml {

    /**
     * The release deploy/compose/compose.yaml runs, from Docker Hub, where Keycloak publishes it too (its digest there
     * is another manifest of the same release): the tests pull from the registry the Postgres image comes from.
     */
    static final String IMAGE = "keycloak/keycloak:26.8.1@sha256:a78d6b79572168cb25886b80a8b5b8d4ed4390743e3eecb810538683e209dd6b";

    static final String REALM = "tinfirm-saml";

    static final String PASSWORD = "test-only-keycloak-password";

    /** Alice and Dave are in the group {@code staff}, Bob in {@code admins}, Carol in none. */
    static final List<String> PEOPLE = List.of("alice", "bob", "carol", "dave");

    private final GenericContainer<?> container;

    private final HttpClient http = HttpClient.newBuilder()
        .followRedirects(HttpClient.Redirect.NEVER)
        .connectTimeout(Duration.ofSeconds(10))
        .build();

    /**
     * @param clients each client's registration id, with the SP's entity ID and ACS URL; an id ending in
     *                {@code enc} encrypts its assertions
     */
    KeycloakSaml(Map<String, String[]> clients, SamlTestKeys sp) {
        container = new GenericContainer<>(IMAGE).withExposedPorts(8080)
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", PASSWORD)
            .withCopyToContainer(Transferable.of(realm(clients, sp)), "/opt/keycloak/data/import/realm.json")
            .withCommand("start-dev", "--import-realm")
            .waitingFor(Wait.forHttp("/realms/" + REALM).forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));
    }

    void start() {
        container.start();
    }

    String base() {
        return "http://localhost:" + container.getMappedPort(8080);
    }

    /** The realm's IdP metadata, as Tinfirm reads it from a file. */
    String metadata() {
        return get(base() + "/realms/" + REALM + "/protocol/saml/descriptor").body();
    }

    /**
     * Signs a person in on Keycloak for the AuthnRequest in {@code location}, the start's redirect: the login form,
     * the password, and Keycloak's self-submitting form back to the ACS, whose fields are returned.
     */
    Map<String, String> login(String location, String username) {
        Map<String, String> cookies = new LinkedHashMap<>();
        HttpResponse<String> page = send(HttpRequest.newBuilder(URI.create(location)).GET(), cookies);
        Matcher action = Pattern.compile("<form[^>]*id=\"kc-form-login\"[^>]*action=\"([^\"]+)\"")
            .matcher(page.body());
        if (!action.find()) {
            throw new IllegalStateException("No login form at " + location + ": " + page.statusCode() + " "
                    + page.body());
        }
        String form = "username=" + encode(username) + "&password=" + encode(PASSWORD) + "&credentialId=";
        HttpResponse<String> posted = send(HttpRequest.newBuilder(URI.create(unescape(action.group(1))))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString(form)), cookies);
        Map<String, String> fields = new LinkedHashMap<>();
        Matcher input = Pattern.compile("<input[^>]*name=\"(SAMLResponse|RelayState)\"[^>]*value=\"([^\"]*)\"")
            .matcher(posted.body());
        while (input.find()) {
            fields.put(input.group(1), unescape(input.group(2)));
        }
        if (!fields.containsKey("SAMLResponse")) {
            throw new IllegalStateException("No SAMLResponse after the login: " + posted.statusCode() + " "
                    + posted.body());
        }
        return fields;
    }

    private HttpResponse<String> get(String url) {
        return send(HttpRequest.newBuilder(URI.create(url)).GET(), new LinkedHashMap<>());
    }

    private HttpResponse<String> send(HttpRequest.Builder request, Map<String, String> cookies) {
        if (!cookies.isEmpty()) {
            request.header("Cookie", String.join("; ", cookies.entrySet()
                .stream()
                .map(cookie -> cookie.getKey() + "=" + cookie.getValue())
                .toList()));
        }
        try {
            HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
            for (String header : response.headers().allValues("Set-Cookie")) {
                String pair = header.split(";", 2)[0];
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    cookies.put(pair.substring(0, equals), pair.substring(equals + 1));
                }
            }
            return response;
        }
        catch (IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String unescape(String html) {
        return html.replace("&amp;", "&").replace("&quot;", "\"").replace("&#x2F;", "/").replace("&#x3D;", "=")
            .replace("&#43;", "+").replace("&#x2B;", "+").replace("&lt;", "<").replace("&gt;", ">")
            .replace("&#61;", "=").replace("&#47;", "/");
    }

    private static String realm(Map<String, String[]> clients, SamlTestKeys sp) {
        StringBuilder json = new StringBuilder();
        json.append("{\"realm\":\"").append(REALM).append("\",\"enabled\":true,\"sslRequired\":\"none\",")
            .append("\"groups\":[{\"name\":\"staff\"},{\"name\":\"admins\"}],\"clients\":[");
        boolean first = true;
        for (Map.Entry<String, String[]> client : clients.entrySet()) {
            String entityId = client.getValue()[0];
            String acs = client.getValue()[1];
            boolean encrypt = client.getKey().endsWith("enc");
            if (!first) {
                json.append(',');
            }
            first = false;
            json.append("""
                    {"clientId":"%s","protocol":"saml","enabled":true,"frontchannelLogout":false,
                     "redirectUris":["%s"],
                     "attributes":{"saml_assertion_consumer_url_post":"%s","saml.server.signature":"true",
                       "saml.assertion.signature":"true","saml.signature.algorithm":"RSA_SHA256",
                       "saml.client.signature":"true","saml.signing.certificate":"%s",
                       "saml_name_id_format":"persistent","saml_force_name_id_format":"true",
                       "saml.authnstatement":"true","saml.encrypt":"%s","saml.encryption.certificate":"%s",
                       "saml.encryption.algorithm":"http://www.w3.org/2009/xmlenc11#aes256-gcm",
                       "saml.encryption.keyAlgorithm":"http://www.w3.org/2009/xmlenc11#rsa-oaep",
                       "saml.encryption.digestMethod":"http://www.w3.org/2001/04/xmlenc#sha256"},
                     "protocolMappers":[
                      {"name":"email","protocol":"saml","protocolMapper":"saml-user-property-mapper",
                       "config":{"user.attribute":"email","attribute.name":"email","attribute.nameformat":"Basic"}},
                      {"name":"givenName","protocol":"saml","protocolMapper":"saml-user-property-mapper",
                       "config":{"user.attribute":"firstName","attribute.name":"givenName",
                                 "attribute.nameformat":"Basic"}},
                      {"name":"surname","protocol":"saml","protocolMapper":"saml-user-property-mapper",
                       "config":{"user.attribute":"lastName","attribute.name":"surname",
                                 "attribute.nameformat":"Basic"}},
                      {"name":"groups","protocol":"saml","protocolMapper":"saml-group-membership-mapper",
                       "config":{"attribute.name":"groups","single":"false","full.path":"false",
                                 "attribute.nameformat":"Basic"}}]}
                    """.formatted(entityId, acs, acs, sp.certificateBase64(), encrypt, sp.certificateBase64()));
        }
        json.append("],\"users\":[");
        String[][] people = { { "alice", "Alice", "Example", "staff" }, { "bob", "Bob", "Example", "admins" },
                { "carol", "Carol", "Example", null }, { "dave", "Dave", "Example", "staff" } };
        for (int i = 0; i < people.length; i++) {
            String[] person = people[i];
            if (i > 0) {
                json.append(',');
            }
            json.append("""
                    {"username":"%s","email":"%s@saml.example","emailVerified":true,"firstName":"%s",
                     "lastName":"%s","enabled":true,"requiredActions":[],%s
                     "credentials":[{"type":"password","value":"%s","temporary":false}]}
                    """.formatted(person[0], person[0], person[1], person[2],
                    person[3] == null ? "" : "\"groups\":[\"/" + person[3] + "\"],", PASSWORD));
        }
        json.append("]}");
        return json.toString();
    }
}
