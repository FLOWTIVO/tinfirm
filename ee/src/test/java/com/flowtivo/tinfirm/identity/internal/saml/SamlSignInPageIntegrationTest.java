package com.flowtivo.tinfirm.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;

/**
 * What the app shows of the SAML providers (ADR-0086, decisions 2 and 15): the login page's buttons, the company's
 * sign-in page with what the IdP's admin needs, and the service provider's metadata.
 */
class SamlSignInPageIntegrationTest extends SamlIntegrationTest {

    @Test
    void theLoginPageListsTheSamlProvidersTheLicenceServes() {
        JsonNode providers = new ApiClient(PORT).get("/api/auth/providers").json().get("providers");

        assertThat(ids(providers, "SAML")).containsExactly("kc", "kcenc", "stub", "stubenc", "stubuid");
        for (JsonNode provider : providers) {
            if (provider.get("id").asString().equals("stub")) {
                assertThat(provider.get("name").asString()).isEqualTo("Stub SAML");
                assertThat(provider.get("loginUrl").asString()).isEqualTo("/saml2/authenticate/stub");
            }
        }

        TestLicences.clear();
        try {
            assertThat(ids(new ApiClient(PORT).get("/api/auth/providers").json().get("providers"), "SAML")).isEmpty();
        }
        finally {
            LocalDate today = TestLicences.today();
            TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.plusYears(1));
        }
    }

    @Test
    void theCompanysSignInPageShowsWhatTheIdpsAdminNeeds() {
        ApiClient member = new ApiClient(PORT);
        Started started = start(member, "stub");
        SamlTestIdp.ResponseBuilder response = STUB.respond(spEntityId("stub"), acs("stub"), started.requestId());
        response.attribute("email", "page-" + UUID.randomUUID() + "@example.com");
        post(member, "stub", response.encoded());
        String companyId = member.get("/api/me").json().get("memberships").get(0).get("companyId").asString();
        assertThat(member.get("/api/companies/" + companyId + "/sign-in").status()).as("a MEMBER").isEqualTo(403);

        ApiClient admin = new ApiClient(PORT);
        admin.login("admin@tinfirm.test", "test-admin-password-1");
        ApiResponse page = admin.get("/api/companies/" + companyId + "/sign-in");

        assertThat(page.status()).as("%s", page).isEqualTo(200);
        assertThat(page.json().get("samlServed").asBoolean()).isTrue();
        JsonNode providers = page.json().get("providers");
        assertThat(providers).hasSize(1);
        JsonNode stub = providers.get(0);
        assertThat(stub.get("id").asString()).isEqualTo("stub");
        assertThat(stub.get("kind").asString()).isEqualTo("SAML");
        assertThat(stub.get("role").asString()).isEqualTo("MEMBER");
        assertThat(stub.get("roleSource").asString()).isEqualTo("APP");
        assertThat(stub.get("directLink").asString()).isEqualTo(BASE + "/login?provider=stub");
        JsonNode saml = stub.get("saml");
        assertThat(saml.get("spEntityId").asString()).isEqualTo(spEntityId("stub"));
        assertThat(saml.get("acsUrl").asString()).isEqualTo(acs("stub"));
        assertThat(saml.get("spMetadataUrl").asString()).isEqualTo(spEntityId("stub"));
        assertThat(saml.get("startUrl").asString()).isEqualTo(BASE + "/saml2/authenticate/stub");
        assertThat(saml.get("idpEntityId").asString()).isEqualTo(STUB.entityId);
        assertThat(saml.get("metadataSource").asString()).isEqualTo("file");
        assertThat(saml.get("metadataReadAt").asString()).isNotBlank();
        assertThat(saml.has("lastError")).as("no error, left out").isFalse();
        assertThat(saml.get("idpCertificates").get(0).get("sha256").asString())
            .isEqualTo(SamlIdpMetadata.fingerprint(STUB.keys.certificate()));
        assertThat(saml.get("spCertificates").get(0).get("sha256").asString())
            .isEqualTo(SamlIdpMetadata.fingerprint(SP.certificate()));
    }

    @Test
    void aSignInReturnsToTheOAuthAuthorizationRequestItInterrupted() {
        ApiClient browser = new ApiClient(PORT);
        String resource = BASE + "/api/companies/" + UUID.randomUUID() + "/mcp";
        String authorize = "/oauth2/authorize?response_type=code&client_id=mcp-inspector&redirect_uri="
                + encode("http://localhost:6274/oauth/callback") + "&scope=tinfirm%3Aread&state=client-state"
                + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&resource="
                + encode(resource);
        assertThat(browser.get(authorize).status()).isEqualTo(302);
        Started started = start(browser, "stub");
        SamlTestIdp.ResponseBuilder response = STUB.respond(spEntityId("stub"), acs("stub"), started.requestId());
        response.attribute("email", "oauth-" + UUID.randomUUID() + "@example.com");
        // The IdP's post is cross-site: the browser sends no SameSite=Lax session cookie with it.
        browser.removeCookie("TINFIRM_SESSION");

        ApiResponse acs = post(browser, "stub", response.encoded());

        assertThat(acs.header("Location")).isEqualTo(BASE + authorize);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void theServiceProvidersMetadataIsPublic() {
        ApiResponse metadata = new ApiClient(PORT).send("GET", "/saml2/service-provider-metadata/stub", null,
                java.util.Map.of("Accept", "application/samlmetadata+xml"));

        assertThat(metadata.status()).as("%s", metadata).isEqualTo(200);
        assertThat(metadata.contentType()).startsWith("application/samlmetadata+xml");
        assertThat(metadata.body()).contains("entityID=\"" + spEntityId("stub") + "\"")
            .contains("AuthnRequestsSigned=\"true\"")
            .contains("WantAssertionsSigned=\"true\"")
            .contains("Location=\"" + acs("stub") + "\"")
            .contains("urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST")
            .contains("urn:oasis:names:tc:SAML:2.0:nameid-format:persistent")
            .contains("urn:oasis:names:tc:SAML:1.1:nameid-format:emailAddress")
            .contains("use=\"signing\"")
            .contains("use=\"encryption\"")
            .contains(SP.certificateBase64().substring(0, 40))
            .doesNotContain("SingleLogoutService");
        assertThat(new ApiClient(PORT).get("/saml2/service-provider-metadata/nosuch").status()).isNotEqualTo(200);
    }

    private static List<String> ids(JsonNode providers, String kind) {
        List<String> ids = new ArrayList<>();
        for (JsonNode provider : providers) {
            if (provider.get("kind").asString().equals(kind)) {
                ids.add(provider.get("id").asString());
            }
        }
        return ids;
    }
}
