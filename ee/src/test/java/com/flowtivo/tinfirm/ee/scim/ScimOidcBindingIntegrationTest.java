package com.flowtivo.tinfirm.ee.scim;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.OidcIntegrationTest;
import com.flowtivo.tinfirm.support.OidcProviderStub.Identity;
import com.flowtivo.tinfirm.support.TestDatabase;

import tools.jackson.databind.JsonNode;

/**
 * ADR-0087, decision 6, and ADR-0088, decision 8: a user SCIM created is bound to their first sign-in through an OIDC
 * provider of the same company, by the IdP's {@code externalId} (OIDC's {@code sub}), or by email where the provider
 * sets {@code _SCIM_MATCH=EXTERNAL_ID_OR_EMAIL}; never to an OWNER or an instance admin, and never through the
 * provider of a company whose SCIM adopted a user another company's SCIM created (H1 of the security review of
 * 2026-10-27); anyone else is refused under {@code _AUTO_PROVISION=false}. SCIM's role wins over the sign-in's claim.
 * <p>
 * The registrations and the declared companies are {@link OidcIntegrationTest}'s.
 */
class ScimOidcBindingIntegrationTest extends OidcIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@tinfirm.test";

    private static final String ADMIN_PASSWORD = "test-admin-password-1";

    private final HttpClient idpBrowser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Test
    void aFirstSignInBindsTheScimUserByItsExternalIdAndScimsRoleWins() {
        ScimTest.Scim scim = scim();
        String email = email("bound");
        String subject = "sub-" + UUID.randomUUID();
        String id = scim.create(ScimTest.user(email, subject, "member"));

        ApiClient browser = new ApiClient(PORT);
        ApiResponse callback = login(browser, SCIM_BY_ID, identity(subject, email, List.of("admins")));

        assertThat(callback.header("Location")).as("%s", callback).isEqualTo("http://localhost:" + PORT + "/");
        JsonNode me = browser.get("/api/me").json();
        assertThat(me.get("id").asString()).isEqualTo(id);
        assertThat(me.get("authSource").asString()).isEqualTo("OIDC");
        // SCIM decides the role; the sign-in's claim (admins) changes nothing.
        assertThat(me.get("memberships").get(0).get("role").asString()).isEqualTo("MEMBER");
        ApiClient owner = admin();
        UUID companyId = UUID.fromString(me.get("memberships").get(0).get("companyId").asString());
        assertThat(events(owner, companyId, "user.identity_bound")).anySatisfy(event -> {
            assertThat(event.get("details").get("userId").asString()).isEqualTo(id);
            assertThat(event.get("details").get("provider").asString()).isEqualTo(SCIM_BY_ID);
            assertThat(event.get("details").get("by").asString()).isEqualTo("externalId");
        });

        // From now on the identity signs in as any other.
        ApiClient again = new ApiClient(PORT);
        assertThat(login(again, SCIM_BY_ID, identity(subject, email, List.of("staff"))).header("Location"))
            .isEqualTo("http://localhost:" + PORT + "/");
        assertThat(again.get("/api/me").json().get("id").asString()).isEqualTo(id);
    }

    @Test
    void anIdentityThatMatchesNoScimUserIsRefusedWithoutAutoProvisioning() {
        ScimTest.Scim scim = scim();
        String email = email("other");
        scim.create(ScimTest.user(email, "sub-" + UUID.randomUUID(), "member"));

        ApiResponse callback = login(new ApiClient(PORT), SCIM_BY_ID,
                identity("sub-" + UUID.randomUUID(), email, List.of("staff")));

        assertThat(callback.header("Location")).contains("/login?error=");
    }

    @Test
    void aProviderThatMatchesByEmailBindsTheScimUserOfThatEmail() {
        ScimTest.Scim scim = scim();
        String email = email("byemail");
        String id = scim.create(ScimTest.user(email, "another-id-" + UUID.randomUUID(), "member"));

        ApiClient browser = new ApiClient(PORT);
        ApiResponse callback = login(browser, SCIM_BY_EMAIL, identity("sub-" + UUID.randomUUID(), email, List.of()));

        assertThat(callback.header("Location")).as("%s", callback).isEqualTo("http://localhost:" + PORT + "/");
        assertThat(browser.get("/api/me").json().get("id").asString()).isEqualTo(id);
        // The provider that matches by externalId only does not bind by email.
        String other = email("idonly");
        scim.create(ScimTest.user(other, "yet-another-" + UUID.randomUUID(), "member"));
        assertThat(login(new ApiClient(PORT), SCIM_BY_ID, identity("sub-" + UUID.randomUUID(), other, List.of("staff")))
            .header("Location")).contains("/login?error=");
    }

    @Test
    void noSignInIsBoundToAnOwner() {
        ScimTest.Scim scim = scim();
        String email = email("owner");
        String subject = "sub-" + UUID.randomUUID();
        String id = scim.create(ScimTest.user(email, subject, "member"));
        ApiClient admin = admin();
        UUID companyId = companyId("scimbind");
        assertThat(admin.patch("/api/companies/" + companyId + "/members/" + id, Map.of("role", "OWNER")).status())
            .isEqualTo(200);

        ApiResponse callback = login(new ApiClient(PORT), SCIM_BY_ID, identity(subject, email, List.of("staff")));

        assertThat(callback.header("Location")).contains("/login?error=");
    }

    /** M16: an instance admin's account is never pointed at a subject a company's SCIM token chose. */
    @Test
    void noSignInIsBoundToAnInstanceAdmin() {
        ScimTest.Scim scim = scim();
        String email = email("instanceadmin");
        String subject = "sub-" + UUID.randomUUID();
        String id = scim.create(ScimTest.user(email, subject, "member"));
        ApiResponse granted = admin().patch("/api/admin/users/" + id, Map.of("instanceAdmin", true));
        assertThat(granted.status()).as("%s", granted).isEqualTo(200);

        ApiResponse callback = login(new ApiClient(PORT), SCIM_BY_ID, identity(subject, email, List.of("staff")));

        assertThat(callback.header("Location")).as("%s", callback).contains("/login?error=");
        assertThat(boundSubject(id)).isNull();
    }

    /**
     * H1: company B's SCIM creates a person; company A's SCIM adopts them by email with an {@code externalId} of A's
     * choosing; a sign-in through A's provider with that subject, or asserting the email, is refused and binds
     * nothing. The person's own first sign-in through B's provider binds them.
     */
    @Test
    void aUserAnotherCompanysScimCreatedIsNeverBoundThroughThisCompanysProvider() {
        String email = email("crosscompany");
        String theirSubject = "sub-" + UUID.randomUUID();
        String id = scim("scimbindapp").create(ScimTest.user(email, theirSubject, "member"));
        String chosen = "sub-" + UUID.randomUUID();
        assertThat(scim().create(ScimTest.user(email, chosen, "member"))).as("adopted by email").isEqualTo(id);

        ApiResponse byId = login(new ApiClient(PORT), SCIM_BY_ID, identity(chosen, email, List.of("staff")));
        ApiResponse byEmail = login(new ApiClient(PORT), SCIM_BY_EMAIL,
                identity("sub-" + UUID.randomUUID(), email, List.of()));

        assertThat(byId.header("Location")).as("%s", byId).contains("/login?error=");
        assertThat(byEmail.header("Location")).as("%s", byEmail).contains("/login?error=");
        assertThat(boundSubject(id)).isNull();
        assertThat(events(admin(), companyId("scimbind"), "user.identity_bound"))
            .noneSatisfy(event -> assertThat(event.get("details").get("userId").asString()).isEqualTo(id));

        ApiClient browser = new ApiClient(PORT);
        assertThat(login(browser, SCIM_APP, identity(theirSubject, email, List.of("staff"))).header("Location"))
            .isEqualTo("http://localhost:" + PORT + "/");
        assertThat(browser.get("/api/me").json().get("id").asString()).isEqualTo(id);
        assertThat(boundSubject(id)).isEqualTo(theirSubject);
    }

    @Test
    void aFirstSignInRefusedForItsRoleBindsNothing() {
        ScimTest.Scim scim = scim("scimbindapp");
        String email = email("unmapped");
        String subject = "sub-" + UUID.randomUUID();
        String id = scim.create(ScimTest.user(email, subject, "member"));

        // The provider decides roles here, and maps no group of this sign-in: refused before anything is bound.
        ApiResponse refused = login(new ApiClient(PORT), SCIM_APP, identity(subject, email, List.of("contractors")));

        assertThat(refused.header("Location")).as("%s", refused).contains("/login?error=");
        assertThat(boundSubject(id)).isNull();
        assertThat(events(admin(), companyId("scimbindapp"), "user.identity_bound"))
            .noneSatisfy(event -> assertThat(event.get("details").get("userId").asString()).isEqualTo(id));

        // Once the provider maps the person's group, the first sign-in binds them.
        ApiClient browser = new ApiClient(PORT);
        assertThat(login(browser, SCIM_APP, identity(subject, email, List.of("staff"))).header("Location"))
            .isEqualTo("http://localhost:" + PORT + "/");
        assertThat(browser.get("/api/me").json().get("id").asString()).isEqualTo(id);
        assertThat(boundSubject(id)).isEqualTo(subject);
    }

    // ---- helpers --------------------------------------------------------------------------------------------------

    private ScimTest.Scim scim() {
        return scim("scimbind");
    }

    private ScimTest.Scim scim(String key) {
        ApiClient admin = admin();
        UUID companyId = companyId(key);
        ApiResponse issued = admin.post("/api/companies/" + companyId + "/scim/tokens",
                Map.of("name", "binding-" + UUID.randomUUID()));
        if (issued.status() == 409) {
            // Two active tokens at most: revoke the older ones the earlier tests issued.
            for (JsonNode token : admin.get("/api/companies/" + companyId + "/scim/tokens").json().get("items")) {
                admin.delete("/api/companies/" + companyId + "/scim/tokens/" + token.get("id").asString());
            }
            issued = admin.post("/api/companies/" + companyId + "/scim/tokens", Map.of("name", "binding"));
        }
        assertThat(issued.status()).as("%s", issued).isEqualTo(201);
        return new ScimTest.Scim(new ApiClient(PORT), issued.json().get("secret").asString());
    }

    /** A declared company, by its key (the shared database holds many companies). */
    private static UUID companyId(String key) {
        try (java.sql.Connection connection = TestDatabase.connectAsOwner();
                java.sql.PreparedStatement system = connection.prepareStatement(
                        "SELECT set_config('app.system', 'true', false)");
                java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT id FROM company WHERE key = ?")) {
            system.execute();
            statement.setString(1, key);
            try (java.sql.ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("the declared company %s exists", key).isTrue();
                return row.getObject(1, UUID.class);
            }
        }
        catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The OIDC subject the user is bound to, or null. */
    private static String boundSubject(String userId) {
        try (java.sql.Connection connection = TestDatabase.connectAsOwner();
                java.sql.PreparedStatement system = connection.prepareStatement(
                        "SELECT set_config('app.system', 'true', false)");
                java.sql.PreparedStatement statement = connection.prepareStatement(
                        "SELECT oidc_subject FROM app_user WHERE id = ?")) {
            system.execute();
            statement.setObject(1, UUID.fromString(userId));
            try (java.sql.ResultSet row = statement.executeQuery()) {
                assertThat(row.next()).as("the user %s exists", userId).isTrue();
                return row.getString(1);
            }
        }
        catch (java.sql.SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static ApiClient admin() {
        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);
        return admin;
    }

    private static List<JsonNode> events(ApiClient reader, UUID companyId, String action) {
        List<JsonNode> events = new java.util.ArrayList<>();
        reader.get("/api/companies/" + companyId + "/audit-events?limit=200").json().get("items").forEach(event -> {
            if (event.get("action").asString().equals(action)) {
                events.add(event);
            }
        });
        return events;
    }

    private static String email(String name) {
        return name + "-" + UUID.randomUUID() + "@scim.example";
    }

    private static Identity identity(String subject, String email, List<String> groups) {
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("email", email);
        claims.put("email_verified", true);
        claims.put("name", "Bound Person");
        claims.put("groups", groups);
        return new Identity(subject, claims);
    }

    /** The authorization code flow; the callback's answer. */
    private ApiResponse login(ApiClient browser, String registrationId, Identity identity) {
        IDP.nextLogin(identity);
        ApiResponse start = browser.get("/oauth2/authorization/" + registrationId);
        assertThat(start.status()).isEqualTo(302);
        URI callback = URI.create(idpRedirect(start.header("Location")));
        return browser.get(callback.getRawPath() + "?" + callback.getRawQuery());
    }

    private String idpRedirect(String authorizationUrl) {
        try {
            HttpResponse<Void> response = idpBrowser.send(HttpRequest.newBuilder(URI.create(authorizationUrl)).build(),
                    HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(302);
            return response.headers().firstValue("Location").orElseThrow();
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
