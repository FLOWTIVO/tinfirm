package com.flowtivo.tinfirm.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;

/**
 * The whole SP-initiated flow against Keycloak, without a browser (ADR-0086, decision 16): the start, Keycloak's login
 * form, the posted response, the ACS and the session; signed, then signed and encrypted.
 */
class SamlKeycloakIntegrationTest extends SamlIntegrationTest {

    @Test
    void aPersonSignsInThroughKeycloakAndTheirGroupGivesTheRole() {
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "kc");
        assertThat(started.location()).startsWith(KEYCLOAK.base() + "/realms/" + KeycloakSaml.REALM)
            .contains("SigAlg=")
            .contains("Signature=");

        Map<String, String> form = KEYCLOAK.login(started.location(), "alice");
        ApiResponse acs = post(browser, "kc", form.get("SAMLResponse"));

        assertThat(acs.status()).as("%s", acs).isEqualTo(302);
        assertThat(acs.header("Location")).isEqualTo(BASE + "/");
        JsonNode me = browser.get("/api/me").json();
        assertThat(me.get("email").asString()).isEqualTo("alice@saml.example");
        assertThat(me.get("displayName").asString()).isEqualTo("Alice Example");
        assertThat(me.get("authSource").asString()).isEqualTo("SAML");
        assertThat(me.get("memberships").get(0).get("role").asString()).isEqualTo("MEMBER");
        assertThat(me.get("memberships").get(0).get("companyName").asString()).isEqualTo("SAML Keycloak Co");
    }

    @Test
    void anAdminsGroupGivesAdmin() {
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "kc");
        ApiResponse acs = post(browser, "kc", KEYCLOAK.login(started.location(), "bob").get("SAMLResponse"));

        assertThat(acs.header("Location")).isEqualTo(BASE + "/");
        JsonNode membership = browser.get("/api/me").json().get("memberships").get(0);
        assertThat(membership.get("role").asString()).isEqualTo("ADMIN");

        // The members page says who decides the role, by the SAML provider's name (ADR-0088).
        JsonNode members = browser.get("/api/companies/" + membership.get("companyId").asString() + "/members?limit=100")
            .json()
            .get("items");
        JsonNode bob = null;
        for (JsonNode member : members) {
            if ("bob@saml.example".equals(member.path("email").asString(""))) {
                bob = member;
            }
        }
        assertThat(bob).as("%s", members).isNotNull();
        assertThat(bob.get("roleManagedBy").get("source").asString()).isEqualTo("IDP");
        assertThat(bob.get("roleManagedBy").get("provider").asString()).isEqualTo("kc");
        assertThat(bob.get("roleManagedBy").get("name").asString()).isEqualTo("Keycloak SAML");
    }

    @Test
    void aPersonInNoMappedGroupIsRefused() {
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "kc");
        ApiResponse acs = post(browser, "kc", KEYCLOAK.login(started.location(), "carol").get("SAMLResponse"));

        assertThat(acs.header("Location")).isEqualTo(BASE + "/login?error=ROLE_NOT_MAPPED");
        assertThat(browser.get("/api/me").status()).isEqualTo(401);
    }

    @Test
    void anEncryptedAssertionSignsInWhereTheProviderRequiresOne() {
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "kcenc");
        // Another person than the other tests': Keycloak names each client's people by a NameID of its own, and one
        // email is one user (EMAIL_IN_USE).
        String response = KEYCLOAK.login(started.location(), "dave").get("SAMLResponse");
        assertThat(new String(java.util.Base64.getDecoder().decode(response))).contains("EncryptedAssertion");

        ApiResponse acs = post(browser, "kcenc", response);

        assertThat(acs.header("Location")).as("%s", acs).isEqualTo(BASE + "/");
        JsonNode me = browser.get("/api/me").json();
        assertThat(me.get("email").asString()).isEqualTo("dave@saml.example");
    }

    @Test
    void keycloaksResponseIsUsedOnce() {
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "kc");
        String response = KEYCLOAK.login(started.location(), "alice").get("SAMLResponse");
        assertThat(post(browser, "kc", response).header("Location")).isEqualTo(BASE + "/");

        ApiClient other = new ApiClient(PORT);
        start(other, "kc");
        ApiResponse again = post(other, "kc", response);

        assertThat(again.header("Location")).isEqualTo(BASE + "/login?error=SAML_LOGIN_FAILED");
        assertThat(other.get("/api/me").status()).isEqualTo(401);
    }
}
