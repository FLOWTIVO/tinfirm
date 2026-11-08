package com.flowtivo.tinfirm.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.support.SamlTestKeys;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.TestDatabase;
import com.flowtivo.tinfirm.support.TestIdp;

/**
 * TEST FIXTURE ONLY: the base of the SAML sign-in tests (ADR-0086, decision 16), one context for all of them. The
 * server runs on a fixed port that is also its {@code TINFIRM_PUBLIC_URL}, as in production, under an Enterprise key.
 * <ul>
 * <li>{@code kc}: Keycloak, response and assertion signed, roles of the company {@code samlkc} from {@code groups}
 * ({@code admins} ADMIN, {@code staff} MEMBER).</li>
 * <li>{@code kcenc}: Keycloak, the assertion also encrypted for the SP's key, which the provider requires; new users
 * of {@code samlkcenc} are MEMBERs, the app deciding after.</li>
 * <li>{@code stub}: {@link SamlTestIdp}, whose responses the tests build, for {@code samlstub}.</li>
 * <li>{@code stubenc}: the same IdP, requiring an encrypted assertion; {@code stubuid}: the same, the subject from
 * the attribute {@code uid}. Both for {@code samlstub2}.</li>
 * </ul>
 * Keycloak and the stub live as long as the cached context, until the test JVM ends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@Import(TestLicences.class)
@ExtendWith(EnterpriseLicence.class)
abstract class SamlIntegrationTest {

    protected static final int PORT = freePort();

    protected static final String BASE = "http://localhost:" + PORT;

    protected static final SamlTestKeys SP = SamlTestKeys.generate("tinfirm-test-sp");

    protected static final SamlTestIdp STUB = new SamlTestIdp("https://idp.test/stub");

    protected static final KeycloakSaml KEYCLOAK;

    private static final Path METADATA;

    static {
        Map<String, String[]> clients = new LinkedHashMap<>();
        for (String id : new String[] { "kc", "kcenc" }) {
            clients.put(id, new String[] { spEntityId(id), acs(id) });
        }
        KEYCLOAK = new KeycloakSaml(clients, SP);
        KEYCLOAK.start();
        try {
            METADATA = Files.createTempDirectory("tinfirm-saml-metadata");
            Files.writeString(METADATA.resolve("keycloak.xml"), KEYCLOAK.metadata());
            Files.writeString(METADATA.resolve("stub.xml"), STUB.metadata());
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", TestDatabase::jdbcUrl);
        registry.add("spring.datasource.username", () -> TestDatabase.APP);
        registry.add("spring.datasource.password", () -> TestDatabase.APP_PASSWORD);
        registry.add("spring.flyway.user", () -> TestDatabase.OWNER);
        registry.add("spring.flyway.password", () -> TestDatabase.OWNER_PASSWORD);
        TestIdp.register(registry);
        registry.add("server.port", () -> PORT);
        registry.add("tinfirm.public-url", () -> BASE);
        registry.add("tinfirm.auth.saml.sp.credentials.k1.key", SP::privateKeyPem);
        registry.add("tinfirm.auth.saml.sp.credentials.k1.cert", SP::certificatePem);
        registry.add("tinfirm.auth.saml.sp.active-credential", () -> "k1");
        company(registry, "samlkc", "SAML Keycloak Co");
        provider(registry, "kc", "keycloak.xml", "samlkc", "Keycloak SAML");
        registry.add("tinfirm.auth.saml.providers.kc.email-domains", () -> "saml.example");
        registry.add("tinfirm.auth.saml.providers.kc.role-claim", () -> "groups");
        registry.add("tinfirm.auth.saml.providers.kc.role-map-admin", () -> "admins");
        registry.add("tinfirm.auth.saml.providers.kc.role-map-member", () -> "staff");
        company(registry, "samlkcenc", "SAML Keycloak Encrypted Co");
        provider(registry, "kcenc", "keycloak.xml", "samlkcenc", "Keycloak SAML, encrypted");
        registry.add("tinfirm.auth.saml.providers.kcenc.require-encrypted-assertions", () -> "true");
        company(registry, "samlstub", "SAML Stub Co");
        provider(registry, "stub", "stub.xml", "samlstub", "Stub SAML");
        company(registry, "samlstub2", "SAML Stub Two Co");
        provider(registry, "stubenc", "stub.xml", "samlstub2", "Stub SAML, encrypted");
        registry.add("tinfirm.auth.saml.providers.stubenc.require-encrypted-assertions", () -> "true");
        provider(registry, "stubuid", "stub.xml", "samlstub2", "Stub SAML, uid");
        registry.add("tinfirm.auth.saml.providers.stubuid.subject", () -> "uid");
    }

    private static void company(DynamicPropertyRegistry registry, String key, String name) {
        registry.add("tinfirm.companies." + key + ".name", () -> name);
    }

    private static void provider(DynamicPropertyRegistry registry, String id, String file, String company,
            String name) {
        String prefix = "tinfirm.auth.saml.providers." + id + ".";
        registry.add(prefix + "metadata-location", () -> "file:" + METADATA.resolve(file));
        registry.add(prefix + "company", () -> company);
        registry.add(prefix + "name", () -> name);
    }

    static String spEntityId(String id) {
        return BASE + "/saml2/service-provider-metadata/" + id;
    }

    static String acs(String id) {
        return BASE + "/login/saml2/sso/" + id;
    }

    /** A sign-in started in the browser: the redirect to the IdP, its request's ID, the binding cookie set. */
    record Started(String location, String requestId) {
    }

    static Started start(ApiClient browser, String id) {
        return start(browser, id, null);
    }

    /** A start that names the page to return to ({@code returnTo}), or none for null. */
    static Started start(ApiClient browser, String id, @org.jspecify.annotations.Nullable String returnTo) {
        ApiResponse start = browser.get("/saml2/authenticate/" + id
                + (returnTo != null ? "?returnTo=" + URLEncoder.encode(returnTo, StandardCharsets.UTF_8) : ""));
        assertThat(start.status()).as("%s", start).isEqualTo(302);
        String binding = start.setCookies()
            .stream()
            .filter(cookie -> cookie.startsWith(SamlAuthnRequests.COOKIE + "="))
            .findFirst()
            .orElseThrow(() -> new AssertionError("No binding cookie: " + start));
        // The cookie is Secure; the tests' browser speaks http to localhost, so it is set by hand.
        browser.setCookie(SamlAuthnRequests.COOKIE, binding.substring(binding.indexOf('=') + 1, binding.indexOf(';')));
        return new Started(start.header("Location"), SamlTestIdp.requestId(start.header("Location")));
    }

    /** Posts a response to the ACS as the IdP's self-submitting form does. */
    static ApiResponse post(ApiClient browser, String id, String samlResponse) {
        return browser.send("POST", "/login/saml2/sso/" + id,
                "SAMLResponse=" + URLEncoder.encode(samlResponse, StandardCharsets.UTF_8),
                Map.of("Content-Type", "application/x-www-form-urlencoded", "Accept", "text/html"));
    }

    /** The newest {@code auth.login.failed} of a provider, as the instance's audit lists it. */
    static tools.jackson.databind.JsonNode latestFailedLogin(String provider) {
        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login("admin@tinfirm.test", "test-admin-password-1").status()).isEqualTo(200);
        for (tools.jackson.databind.JsonNode event : admin.get("/api/admin/audit-events?limit=30").json().get("items")) {
            if (event.get("action").asString().equals("auth.login.failed")
                    && provider.equals(event.path("details").path("provider").asString(""))) {
                return event;
            }
        }
        throw new AssertionError("no auth.login.failed for " + provider);
    }

    private static int freePort() {
        // Only reserves a free local port for the server; no data goes over it.
        try (ServerSocket socket = new ServerSocket(0)) { // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            return socket.getLocalPort();
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
