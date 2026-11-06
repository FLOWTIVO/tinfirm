package com.flowtivo.tinfirm.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.company.CompanyProvisioning;
import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.OidcIntegrationTest;
import com.flowtivo.tinfirm.support.OidcProviderStub;
import com.flowtivo.tinfirm.support.OidcProviderStub.Identity;

import tools.jackson.databind.JsonNode;

/**
 * End-to-end OIDC login against {@link OidcProviderStub}: the registrations and the
 * declared companies are {@link OidcIntegrationTest}'s.
 */
class OidcLoginIntegrationTest extends OidcIntegrationTest {

    private static final String ADMIN_EMAIL = "admin@tinfirm.test";

    private static final String ADMIN_PASSWORD = "test-admin-password-1";

    @Autowired
    private com.flowtivo.tinfirm.identity.ProvisionedMemberships provisionedMemberships;

    private final HttpClient idpBrowser = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build();

    @Autowired
    private CompanyProvisioning companyProvisioning;

    @Test
    void provisionsANewUserWithAVerifiedEmailAndRotatesTheSession() {
        String email = "oidc-" + UUID.randomUUID() + "@example.test";
        ApiClient browser = new ApiClient(PORT);

        ApiResponse callback = login(browser, new Identity("sub-" + UUID.randomUUID(), email, true, "Olivia Idc"),
                true);

        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/");
        JsonNode me = browser.get("/api/me").json();
        assertThat(me.get("email").asString()).isEqualTo(email);
        assertThat(me.get("displayName").asString()).isEqualTo("Olivia Idc");
        assertThat(me.get("authSource").asString()).isEqualTo("OIDC");
        assertThat(me.get("instanceAdmin").asBoolean()).isFalse();

        // PKCE, although this is a confidential client: the token request carried client auth and a verifier.
        Map<String, String> tokenRequest = IDP.tokenRequests().getLast();
        assertThat(tokenRequest.get("authorization")).startsWith("Basic ");
        assertThat(tokenRequest.get("code_verifier")).isNotBlank();

        // Audited: SYSTEM created the user, then the user logged in.
        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);
        JsonNode events = admin.get("/api/admin/audit-events?limit=10").json().get("items");
        String userId = me.get("id").asString();
        JsonNode created = event(events, "user.created", userId);
        assertThat(created.get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(created.get("details").get("authSource").asString()).isEqualTo("OIDC");
        JsonNode login = event(events, "auth.login.succeeded", userId);
        assertThat(login.get("details").get("method").asString()).isEqualTo("oidc");
        assertThat(login.get("details").get("provider").asString()).isEqualTo("stub-idp");
    }

    @Test
    void anOidcLoginReturnsToTheOAuthAuthorizationRequestItInterrupted() {
        // An MCP client sent the browser to Tinfirm's authorization endpoint without a session (ADR-0045).
        ApiClient browser = new ApiClient(PORT);
        String resource = "http://localhost:" + PORT + "/api/companies/" + UUID.randomUUID() + "/mcp";
        String authorize = "/oauth2/authorize?response_type=code&client_id=mcp-inspector&redirect_uri="
                + encode("http://localhost:6274/oauth/callback") + "&scope=tinfirm%3Aread&state=client-state"
                + "&code_challenge=E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM&code_challenge_method=S256&resource="
                + encode(resource);
        ApiResponse interrupted = browser.get(authorize);
        assertThat(interrupted.status()).as("%s", interrupted).isEqualTo(302);
        assertThat(interrupted.header("Location"))
            .isEqualTo("http://localhost:" + PORT + "/login?returnTo=" + encode(authorize));

        // The person picks single sign-on on the login page: back to the authorization request, not to "/".
        ApiResponse callback = login(browser,
                new Identity("sub-" + UUID.randomUUID(), "oauth-" + UUID.randomUUID() + "@example.test", true,
                        "Oscar Auth"),
                true);
        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + authorize);

        // The saved request is used once: the next login ends on "/" again (ADR-0008).
        ApiClient other = new ApiClient(PORT);
        ApiResponse plain = login(other,
                new Identity("sub-" + UUID.randomUUID(), "plain-" + UUID.randomUUID() + "@example.test", true,
                        "Pia Plain"),
                false);
        assertThat(plain.header("Location")).isEqualTo("http://localhost:" + PORT + "/");
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void theSameIssuerAndSubjectIsTheSameUserEvenIfTheEmailClaimChanges() {
        String subject = "sub-" + UUID.randomUUID();
        ApiClient first = new ApiClient(PORT);
        login(first, new Identity(subject, "first-" + UUID.randomUUID() + "@example.test", true, "Same Person"), false);
        String firstId = first.get("/api/me").json().get("id").asString();

        ApiClient second = new ApiClient(PORT);
        login(second, new Identity(subject, "changed-" + UUID.randomUUID() + "@example.test", true, "Same Person"),
                false);

        assertThat(second.get("/api/me").json().get("id").asString()).isEqualTo(firstId);
    }

    @Test
    void neverLinksToAnExistingUserByEmail() {
        ApiClient browser = new ApiClient(PORT);

        ApiResponse callback = login(browser, new Identity("sub-" + UUID.randomUUID(), ADMIN_EMAIL, true, "Imposter"),
                false);

        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/login?error=EMAIL_IN_USE");
        assertThat(browser.get("/api/me").status()).isEqualTo(401);
        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).json().get("authSource").asString()).isEqualTo("LOCAL");
    }

    @Test
    void unverifiedEmailsAreNotProvisioned() {
        ApiClient browser = new ApiClient(PORT);

        ApiResponse callback = login(browser,
                new Identity("sub-" + UUID.randomUUID(), "unverified-" + UUID.randomUUID() + "@example.test", false,
                        "Unverified"),
                false);

        assertThat(callback.header("Location"))
            .isEqualTo("http://localhost:" + PORT + "/login?error=EMAIL_NOT_VERIFIED");
        assertThat(browser.get("/api/me").status()).isEqualTo(401);
    }

    @Test
    void oidcUsersHaveNoPassword() {
        ApiClient browser = new ApiClient(PORT);
        login(browser, new Identity("sub-" + UUID.randomUUID(), "nopw-" + UUID.randomUUID() + "@example.test", true,
                "No Password"), false);
        String userId = browser.get("/api/me").json().get("id").asString();

        ApiResponse own = browser.put("/api/me/password",
                Map.of("currentPassword", "anything-at-all", "newPassword", "a-new-password-1"));
        assertThat(own.status()).isEqualTo(409);
        assertThat(own.problemCode()).isEqualTo("NOT_LOCAL_USER");

        ApiClient admin = new ApiClient(PORT);
        admin.login(ADMIN_EMAIL, ADMIN_PASSWORD);
        ApiResponse set = admin.put("/api/admin/users/" + userId + "/password",
                Map.of("newPassword", "a-new-password-1"));
        assertThat(set.status()).isEqualTo(409);
        assertThat(set.problemCode()).isEqualTo("NOT_LOCAL_USER");
    }

    @Test
    void deactivatedOidcUsersCannotLogIn() {
        Identity identity = new Identity("sub-" + UUID.randomUUID(), "gone-" + UUID.randomUUID() + "@example.test",
                true, "Gone");
        ApiClient browser = new ApiClient(PORT);
        login(browser, identity, false);
        String userId = browser.get("/api/me").json().get("id").asString();
        ApiClient admin = new ApiClient(PORT);
        admin.login(ADMIN_EMAIL, ADMIN_PASSWORD);
        assertThat(admin.patch("/api/admin/users/" + userId, Map.of("active", false)).status()).isEqualTo(200);

        assertThat(browser.get("/api/me").status()).isEqualTo(401);
        ApiResponse again = login(new ApiClient(PORT), identity, false);
        assertThat(again.header("Location")).endsWith("/login?error=OIDC_LOGIN_FAILED");
    }

    @Test
    void entraLikeTokensAreRefusedWhileTheIssuerIsNotTrusted() {
        String email = "entra-" + UUID.randomUUID() + "@example.test";
        ApiClient browser = new ApiClient(PORT);

        ApiResponse callback = login(browser, DEFAULT_TRUST,
                entraIdentity(Map.of("email", email, "preferred_username", email, "name", "Not Trusted")), false);

        assertThat(callback.header("Location"))
            .isEqualTo("http://localhost:" + PORT + "/login?error=EMAIL_NOT_VERIFIED");
        assertThat(browser.get("/api/me").status()).isEqualTo(401);
        JsonNode failed = latestFailedLogin(DEFAULT_TRUST);
        assertThat(failed.get("details").get("reason").asString()).isEqualTo("email_not_verified");
        assertThat(failed.toString()).doesNotContain(email);
    }

    @Test
    void aTrustedIssuerProvisionsWithoutEmailVerifiedFromThePreferredUsername() {
        String upn = "Entra.User-" + UUID.randomUUID() + "@Example.test";
        ApiClient browser = new ApiClient(PORT);

        ApiResponse callback = login(browser, ENTRA,
                entraIdentity(Map.of("preferred_username", upn, "name", "Entra User")), true);

        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/");
        JsonNode me = browser.get("/api/me").json();
        String email = upn.toLowerCase(Locale.ROOT);
        assertThat(me.get("email").asString()).isEqualTo(email);
        assertThat(me.get("displayName").asString()).isEqualTo("Entra User");
        assertThat(me.get("authSource").asString()).isEqualTo("OIDC");

        // Audited without the email: the claim it came from, and the login.
        JsonNode events = adminEvents();
        String userId = me.get("id").asString();
        JsonNode created = event(events, "user.created", userId);
        assertThat(created.get("details").get("provider").asString()).isEqualTo(ENTRA);
        assertThat(created.get("details").get("emailClaim").asString()).isEqualTo("preferred_username");
        JsonNode login = event(events, "auth.login.succeeded", userId);
        assertThat(login.get("details").get("provider").asString()).isEqualTo(ENTRA);
        assertThat(created.toString()).doesNotContainIgnoringCase(email);
        assertThat(login.toString()).doesNotContainIgnoringCase(email);
    }

    @Test
    void aTrustedIssuerReadsTheVerifiedPrimaryEmailArray() {
        String email = "primary-" + UUID.randomUUID() + "@example.test";
        ApiClient browser = new ApiClient(PORT);

        login(browser, ENTRA, entraIdentity(Map.of("preferred_username", "not-an-email", "verified_primary_email",
                List.of(email, "secondary@example.test"), "name", "Array Claim")), false);

        assertThat(browser.get("/api/me").json().get("email").asString()).isEqualTo(email);
    }

    @Test
    void aTrustedIssuerWithoutAnyEmailIsRefusedAsEmailMissing() {
        ApiClient browser = new ApiClient(PORT);

        ApiResponse callback = login(browser, ENTRA,
                entraIdentity(Map.of("preferred_username", "no-email-" + UUID.randomUUID(), "name", "No Email")),
                false);

        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/login?error=EMAIL_MISSING");
        assertThat(browser.get("/api/me").status()).isEqualTo(401);
        assertThat(latestFailedLogin(ENTRA).get("details").get("reason").asString()).isEqualTo("email_missing");
    }

    @Test
    void aTrustedIssuerNeverLinksToAnExistingUserByEmail() {
        ApiClient browser = new ApiClient(PORT);

        ApiResponse callback = login(browser, ENTRA, entraIdentity(
                Map.of("preferred_username", ADMIN_EMAIL.toUpperCase(Locale.ROOT), "name", "Imposter")), false);

        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/login?error=EMAIL_IN_USE");
        assertThat(browser.get("/api/me").status()).isEqualTo(401);
    }

    @Test
    void aTrustedIssuersUserLogsInAgainByIssuerAndSubjectAlone() {
        String subject = "entra-sub-" + UUID.randomUUID();
        ApiClient first = new ApiClient(PORT);
        login(first, ENTRA, new Identity(subject,
                Map.of("email", "again-" + UUID.randomUUID() + "@example.test", "name", "Again")), false);
        String firstId = first.get("/api/me").json().get("id").asString();

        ApiClient second = new ApiClient(PORT);
        ApiResponse callback = login(second, ENTRA, new Identity(subject, Map.of()), false);

        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/");
        assertThat(second.get("/api/me").json().get("id").asString()).isEqualTo(firstId);
    }

    /**
     * With two companies declared, a provider that names none gives a new user no membership, and any company can add
     * the user as a member.
     */
    @Test
    void aProviderWithoutACompanyGivesNoMembershipAndAnyCompanyCanAddTheUser() {
        String email = "anywhere-" + UUID.randomUUID() + "@example.test";
        ApiClient browser = new ApiClient(PORT);
        login(browser, new Identity("sub-" + UUID.randomUUID(), email, true, "Andy Anywhere"), false);
        assertThat(browser.get("/api/me").json().get("memberships")).isEmpty();

        ApiClient admin = new ApiClient(PORT);
        JsonNode me = admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).json();
        UUID adminId = UUID.fromString(me.get("id").asString());
        for (String name : List.of("First", "Second")) {
            UUID companyId = SystemContext
                .call(() -> companyProvisioning.createCompany(name + " " + UUID.randomUUID(), adminId));
            ApiResponse added = admin.post("/api/companies/" + companyId + "/members",
                    Map.of("email", email, "role", "MEMBER"));
            assertThat(added.status()).as("%s", added).isEqualTo(201);
        }
        assertThat(browser.get("/api/me").json().get("memberships")).hasSize(2);
    }

    /** A provider that names a declared company makes a new user its member, with the provider's role (ADR-0075). */
    @Test
    void aProviderOfACompanyMakesTheNewUserItsMemberWithTheProvidersRole() {
        String email = "ann-" + UUID.randomUUID() + "@acme.example";
        ApiClient browser = new ApiClient(PORT);

        ApiResponse callback = login(browser, ACME, new Identity("sub-" + UUID.randomUUID(), email, true, "Ann Acme"),
                false);

        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/");
        JsonNode me = browser.get("/api/me").json();
        JsonNode memberships = me.get("memberships");
        assertThat(memberships).hasSize(1);
        assertThat(memberships.get(0).get("companyName").asString()).isEqualTo(ACME_COMPANY);
        assertThat(memberships.get(0).get("role").asString()).isEqualTo("ADMIN");

        String userId = me.get("id").asString();
        String companyId = memberships.get(0).get("companyId").asString();
        JsonNode created = event(adminEvents(), "user.created", userId);
        assertThat(created.get("details").get("companyId").asString()).isEqualTo(companyId);
        assertThat(created.get("details").get("role").asString()).isEqualTo("ADMIN");
        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);
        JsonNode added = event(admin.get("/api/companies/" + companyId + "/audit-events?limit=20").json().get("items"),
                "member.added", userId);
        assertThat(added.get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(added.get("details").get("provider").asString()).isEqualTo(ACME);
    }

    /**
     * ADR-0077, decision 5: signing in never fails for want of a seat. A new user whose provider gives a seat role
     * joins as VIEWER while every seat is taken, and the company's admins see it in their inbox.
     */
    @Test
    void aNewUserJoinsAsAViewerWhenEverySeatIsTaken() {
        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);
        int used = admin.get("/api/licence").json().get("seats").get("used").asInt();
        assertThat(admin.put("/api/admin/licence", Map.of("key", TestLicences.pro(used))).status())
            .isEqualTo(200);
        try {
            String email = "vic-" + UUID.randomUUID() + "@acme.example";
            ApiClient browser = new ApiClient(PORT);

            ApiResponse callback = login(browser, ACME, new Identity("sub-" + UUID.randomUUID(), email, true,
                    "Vic Viewer"), false);

            assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/");
            JsonNode me = browser.get("/api/me").json();
            assertThat(me.get("memberships").get(0).get("role").asString()).isEqualTo("VIEWER");
            String userId = me.get("id").asString();
            String companyId = me.get("memberships").get(0).get("companyId").asString();
            JsonNode added = event(admin.get("/api/companies/" + companyId + "/audit-events?limit=20").json()
                .get("items"), "member.added", userId);
            assertThat(added.get("details").get("role").asString()).isEqualTo("VIEWER");
            assertThat(added.get("details").get("providerRole").asString()).isEqualTo("ADMIN");
            assertThat(added.get("details").get("seatFallback").asBoolean()).isTrue();
            JsonNode inbox = admin.get("/api/companies/" + companyId + "/inbox").json().get("items");
            assertThat(inbox).anySatisfy(item -> {
                assertThat(item.get("kind").asString()).isEqualTo("SEAT_FALLBACK");
                assertThat(item.get("seatFallback").get("userId").asString()).isEqualTo(userId);
                assertThat(item.get("seatFallback").get("role").asString()).isEqualTo("ADMIN");
                assertThat(item.get("dismissible").asBoolean()).isTrue();
            });
        }
        finally {
            TestLicences.clear();
        }
    }

    @Test
    void aProviderOfACompanyRefusesAnEmailOutsideItsDomains() {
        String email = "eve-" + UUID.randomUUID() + "@elsewhere.example";
        ApiClient browser = new ApiClient(PORT);

        ApiResponse callback = login(browser, ACME, new Identity("sub-" + UUID.randomUUID(), email, true, "Eve"),
                false);

        assertThat(callback.header("Location"))
            .isEqualTo("http://localhost:" + PORT + "/login?error=EMAIL_DOMAIN_NOT_ALLOWED");
        assertThat(browser.get("/api/me").status()).isEqualTo(401);
        assertThat(latestFailedLogin(ACME).get("details").get("reason").asString())
            .isEqualTo("email_domain_not_allowed");
    }

    /**
     * A declared company's owner becomes its OWNER at a sign-in through that company's provider that asserts their
     * email, after the login's commit. Another company's provider that asserts the owner's email claims nothing, nor
     * does that company's provider in a login that asserts another (ADR-0075).
     */
    @Test
    void onlyTheCompanysOwnProviderMakesTheDeclaredOwnerItsOwner() {
        Identity identity = new Identity("sub-" + UUID.randomUUID(), BETA_OWNER, true, "Bea Owner");

        ApiClient throughAcme = new ApiClient(PORT);
        login(throughAcme, ACME, identity, false);
        assertThat(roles(throughAcme)).containsExactlyInAnyOrderEntriesOf(Map.of(ACME_COMPANY, "ADMIN"));

        // The same subject through beta's provider, but this login asserts another email: no claim.
        ApiClient assertingAnother = new ApiClient(PORT);
        login(assertingAnother, BETA,
                new Identity(identity.subject(), "someone-" + UUID.randomUUID() + "@acme.example", true, "Bea Owner"),
                false);
        assertThat(roles(assertingAnother)).containsExactlyInAnyOrderEntriesOf(Map.of(ACME_COMPANY, "ADMIN"));

        ApiClient throughBeta = new ApiClient(PORT);
        login(throughBeta, BETA, identity, false);
        assertThat(roles(throughBeta))
            .containsExactlyInAnyOrderEntriesOf(Map.of(ACME_COMPANY, "ADMIN", "Beta Owned Co", "OWNER"));
    }

    /** A known identity only signs in: a membership the company removed is not granted again. */
    @Test
    void aRemovedMemberIsNotMadeAMemberAgainByTheNextSignIn() {
        Identity identity = new Identity("sub-" + UUID.randomUUID(), "bo-" + UUID.randomUUID() + "@acme.example",
                true, "Bo Acme");
        ApiClient first = new ApiClient(PORT);
        login(first, ACME, identity, false);
        JsonNode me = first.get("/api/me").json();
        String companyId = me.get("memberships").get(0).get("companyId").asString();

        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);
        ApiResponse removed = admin.delete("/api/companies/" + companyId + "/members/" + me.get("id").asString());
        assertThat(removed.status()).as("%s", removed).isIn(204, 200);

        ApiClient second = new ApiClient(PORT);
        login(second, ACME, identity, false);
        assertThat(second.get("/api/me").json().get("id").asString()).isEqualTo(me.get("id").asString());
        assertThat(second.get("/api/me").json().get("memberships")).isEmpty();
    }

    /** The signed-in user's role in each of their companies, by the company's name. */
    private static Map<String, String> roles(ApiClient browser) {
        Map<String, String> roles = new HashMap<>();
        browser.get("/api/me")
            .json()
            .get("memberships")
            .forEach(membership -> roles.put(membership.get("companyName").asString(),
                    membership.get("role").asString()));
        return roles;
    }

    /** An Entra ID token: no {@code email_verified}, and only the given claims. */
    private static Identity entraIdentity(Map<String, Object> claims) {
        return new Identity("entra-sub-" + UUID.randomUUID(), claims);
    }

    private ApiResponse login(ApiClient browser, Identity identity, boolean checkSessionRotation) {
        return login(browser, DEFAULT_TRUST, identity, checkSessionRotation);
    }

    /**
     * Runs the authorization code flow: Tinfirm redirects to the IdP, the IdP "logs in" the identity and redirects
     * back, and the browser follows to the callback. Returns the callback response.
     */
    private ApiResponse login(ApiClient browser, String registrationId, Identity identity,
            boolean checkSessionRotation) {
        IDP.nextLogin(identity);
        ApiResponse start = browser.get("/oauth2/authorization/" + registrationId);
        assertThat(start.status()).isEqualTo(302);
        String anonymousSession = browser.cookie("TINFIRM_SESSION").orElseThrow().getValue();

        String callbackUrl = idpRedirect(start.header("Location"));
        URI callback = URI.create(callbackUrl);
        ApiResponse response = browser.get(callback.getRawPath() + "?" + callback.getRawQuery());

        if (checkSessionRotation) {
            assertThat(browser.cookie("TINFIRM_SESSION").orElseThrow().getValue()).isNotEqualTo(anonymousSession);
        }
        return response;
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

    /**
     * ADR-0088: the provider decides the role at every sign-in, a promotion and a demotion alike; the app cannot change
     * it ({@code 409 ROLE_MANAGED}) except to OWNER, which releases it; a claim that maps to no role refuses the
     * sign-in and keeps the membership; an OWNER is neither changed nor refused.
     */
    @Test
    void theProviderDecidesTheRoleAtEverySignIn() {
        String subject = "sub-" + UUID.randomUUID();
        String email = "rita-" + UUID.randomUUID() + "@roles.example";
        ApiClient browser = new ApiClient(PORT);
        login(browser, ROLES, groups(subject, email, List.of("staff", "viewers")), false);
        JsonNode me = browser.get("/api/me").json();
        String userId = me.get("id").asString();
        String companyId = me.get("memberships").get(0).get("companyId").asString();
        assertThat(me.get("memberships").get(0).get("role").asString()).isEqualTo("MEMBER");

        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);
        JsonNode member = member(admin, companyId, userId);
        assertThat(member.get("roleManagedBy").get("source").asString()).isEqualTo("IDP");
        assertThat(member.get("roleManagedBy").get("provider").asString()).isEqualTo(ROLES);
        assertThat(member.get("roleManagedBy").get("name").asString()).isEqualTo("Stub Roles SSO");

        // A promotion, then a demotion, at later sign-ins.
        assertThat(roleAfterSignIn(subject, email, List.of("admins", "staff"))).isEqualTo("ADMIN");
        JsonNode promoted = event(admin.get("/api/companies/" + companyId + "/audit-events?limit=20").json()
            .get("items"), "member.role.changed", userId);
        assertThat(promoted.get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(promoted.get("details").get("previousRole").asString()).isEqualTo("MEMBER");
        assertThat(promoted.get("details").get("role").asString()).isEqualTo("ADMIN");
        assertThat(promoted.get("details").get("roleSource").asString()).isEqualTo("IDP");
        assertThat(promoted.get("details").get("provider").asString()).isEqualTo(ROLES);
        assertThat(roleAfterSignIn(subject, email, List.of("viewers"))).isEqualTo("VIEWER");

        // The app cannot change it, the instance admin's break-glass included.
        ApiResponse changed = admin.patch("/api/companies/" + companyId + "/members/" + userId,
                Map.of("role", "MEMBER"));
        assertThat(changed.status()).isEqualTo(409);
        assertThat(changed.json().get("code").asString()).isEqualTo("ROLE_MANAGED");
        assertThat(changed.json().get("details").get("provider").asString()).isEqualTo(ROLES);

        // A claim that maps to no role refuses the sign-in; the membership and its role stay.
        ApiClient refused = new ApiClient(PORT);
        ApiResponse callback = login(refused, ROLES, groups(subject, email, List.of("contractors")), false);
        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/login?error=ROLE_NOT_MAPPED");
        assertThat(refused.get("/api/me").status()).isEqualTo(401);
        JsonNode failed = latestFailedLogin(ROLES);
        assertThat(failed.get("details").get("reason").asString()).isEqualTo("role_not_mapped");
        assertThat(failed.path("targetId").asString()).isEqualTo(userId);
        assertThat(member(admin, companyId, userId).get("role").asString()).isEqualTo("VIEWER");

        // Made OWNER in the app, they are the provider's no more: never changed, never refused.
        ApiResponse owner = admin.patch("/api/companies/" + companyId + "/members/" + userId, Map.of("role", "OWNER"));
        assertThat(owner.status()).as("%s", owner).isEqualTo(200);
        assertThat(owner.json().path("roleManagedBy").isMissingNode()).isTrue();
        assertThat(roleAfterSignIn(subject, email, List.of("viewers"))).isEqualTo("OWNER");
        assertThat(roleAfterSignIn(subject, email, List.of())).isEqualTo("OWNER");
    }

    /**
     * ADR-0089: one provider fills two companies from its claim. A person joins each company the claim names, with
     * the role mapped there; a company the claim names no more is a departure, audited with the provider; a claim
     * that names no company refuses the sign-in and ends nothing; each company's sign-in page lists the provider.
     */
    @Test
    void theProviderFillsEachCompanyItsClaimNames() {
        String subject = "sub-" + UUID.randomUUID();
        String email = "mona-" + UUID.randomUUID() + "@multi.example";
        ApiClient browser = new ApiClient(PORT);
        login(browser, MULTI, groups(subject, email, List.of("a-staff", "b-admins")), false);
        JsonNode me = browser.get("/api/me").json();
        String userId = me.get("id").asString();
        assertThat(roles(browser)).containsEntry(MULTI_A_COMPANY, "MEMBER").containsEntry(MULTI_B_COMPANY, "ADMIN");
        Map<String, String> companyIds = new HashMap<>();
        me.get("memberships").forEach(membership -> companyIds.put(membership.get("companyName").asString(),
                membership.get("companyId").asString()));
        String companyA = companyIds.get(MULTI_A_COMPANY);
        String companyB = companyIds.get(MULTI_B_COMPANY);

        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);
        assertThat(member(admin, companyB, userId).get("roleManagedBy").get("provider").asString()).isEqualTo(MULTI);
        JsonNode addedToB = event(admin.get("/api/companies/" + companyB + "/audit-events?limit=20").json()
            .get("items"), "member.added", userId);
        assertThat(addedToB.get("details").get("provider").asString()).isEqualTo(MULTI);
        assertThat(addedToB.get("details").get("roleSource").asString()).isEqualTo("IDP");
        for (String companyId : List.of(companyA, companyB)) {
            JsonNode providers = admin.get("/api/companies/" + companyId + "/sign-in").json().get("providers");
            JsonNode multi = null;
            for (JsonNode provider : providers) {
                if (provider.get("id").asString().equals(MULTI)) {
                    multi = provider;
                }
            }
            assertThat(multi).as("the provider on the page of %s", companyId).isNotNull();
            // Its own company A, and B through its claim, each with the values that give each role there.
            assertThat(multi.get("through").asString()).isEqualTo(companyId.equals(companyA) ? "COMPANY" : "CLAIM");
            assertThat(multi.get("roleMap").get("admin").get(0).asString())
                .isEqualTo(companyId.equals(companyA) ? "a-admins" : "b-admins");
        }

        // Out of A's groups: a departure from A at the next sign-in, the role in B kept.
        ApiClient second = new ApiClient(PORT);
        login(second, MULTI, groups(subject, email, List.of("b-staff")), false);
        assertThat(roles(second)).containsOnly(Map.entry(MULTI_B_COMPANY, "MEMBER"));
        JsonNode removed = event(admin.get("/api/companies/" + companyA + "/audit-events?limit=20").json()
            .get("items"), "member.removed", userId);
        assertThat(removed.get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(removed.get("details").get("provider").asString()).isEqualTo(MULTI);
        assertThat(removed.get("details").get("roleSource").asString()).isEqualTo("IDP");
        assertThat(admin.get("/api/companies/" + companyA + "/members?limit=200").json().get("items")
            .findValuesAsString("userId")).doesNotContain(userId);

        // Back into A's groups: a member of A again, with the role mapped there.
        ApiClient third = new ApiClient(PORT);
        login(third, MULTI, groups(subject, email, List.of("a-admins", "b-staff")), false);
        assertThat(roles(third)).containsEntry(MULTI_A_COMPANY, "ADMIN").containsEntry(MULTI_B_COMPANY, "MEMBER");

        // No company at all: refused, and both memberships stay.
        ApiClient refused = new ApiClient(PORT);
        ApiResponse callback = login(refused, MULTI, groups(subject, email, List.of("contractors")), false);
        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/login?error=ROLE_NOT_MAPPED");
        assertThat(roles(third)).containsEntry(MULTI_A_COMPANY, "ADMIN").containsEntry(MULTI_B_COMPANY, "MEMBER");
    }

    @Test
    void aNewUserWhoseClaimMapsToNoRoleIsRefusedAndNotCreated() {
        String email = "nora-" + UUID.randomUUID() + "@roles.example";
        ApiClient browser = new ApiClient(PORT);

        ApiResponse callback = login(browser, ROLES, groups("sub-" + UUID.randomUUID(), email, List.of("guests")),
                false);

        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/login?error=ROLE_NOT_MAPPED");
        assertThat(browser.get("/api/me").status()).isEqualTo(401);
        JsonNode failed = latestFailedLogin(ROLES);
        assertThat(failed.get("details").get("reason").asString()).isEqualTo("role_not_mapped");
        // No user was created: the refusal names none.
        assertThat(failed.path("targetId").isMissingNode() || failed.path("targetId").isNull()).isTrue();
    }

    /**
     * The seat fallback at a later sign-in (ADR-0088 decision 2): the provider's seat role gives VIEWER while every
     * seat is taken, with the admins' item, and the role once a seat is free.
     */
    @Test
    void aSeatRoleAtASignInWaitsForAFreeSeat() {
        String subject = "sub-" + UUID.randomUUID();
        String email = "sam-" + UUID.randomUUID() + "@roles.example";
        ApiClient first = new ApiClient(PORT);
        login(first, ROLES, groups(subject, email, List.of("viewers")), false);
        JsonNode me = first.get("/api/me").json();
        String userId = me.get("id").asString();
        String companyId = me.get("memberships").get(0).get("companyId").asString();

        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);
        int used = admin.get("/api/licence").json().get("seats").get("used").asInt();
        assertThat(admin.put("/api/admin/licence", Map.of("key", TestLicences.pro(used))).status()).isEqualTo(200);
        try {
            assertThat(roleAfterSignIn(subject, email, List.of("engineers"))).isEqualTo("VIEWER");
            JsonNode inbox = admin.get("/api/companies/" + companyId + "/inbox").json().get("items");
            assertThat(inbox).anySatisfy(item -> {
                assertThat(item.get("kind").asString()).isEqualTo("SEAT_FALLBACK");
                assertThat(item.get("seatFallback").get("userId").asString()).isEqualTo(userId);
                assertThat(item.get("seatFallback").get("role").asString()).isEqualTo("MEMBER");
            });

            assertThat(admin.put("/api/admin/licence", Map.of("key", TestLicences.pro(used + 1))).status())
                .isEqualTo(200);
            assertThat(roleAfterSignIn(subject, email, List.of("engineers"))).isEqualTo("MEMBER");
        }
        finally {
            TestLicences.clear();
        }
    }

    /** A provider switched to APP, or removed, releases its memberships at the next start (ADR-0088 decision 7). */
    @Test
    void aProviderThatNoLongerDecidesReleasesItsMemberships() {
        String subject = "sub-" + UUID.randomUUID();
        String email = "rel-" + UUID.randomUUID() + "@roles.example";
        ApiClient browser = new ApiClient(PORT);
        login(browser, ROLES, groups(subject, email, List.of("staff")), false);
        JsonNode me = browser.get("/api/me").json();
        String userId = me.get("id").asString();
        String companyId = me.get("memberships").get(0).get("companyId").asString();

        // What the start does: the deciding providers are those of the deployment, here none.
        provisionedMemberships.releaseProviderRoles(Map.of());

        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);
        JsonNode member = member(admin, companyId, userId);
        assertThat(member.get("role").asString()).isEqualTo("MEMBER");
        assertThat(member.path("roleManagedBy").isMissingNode()).isTrue();
        JsonNode released = admin.get("/api/companies/" + companyId + "/audit-events?limit=20").json().get("items");
        assertThat(released).anySatisfy(event -> {
            assertThat(event.get("action").asString()).isEqualTo("member.role_source.changed");
            assertThat(event.get("details").get("provider").asString()).isEqualTo(ROLES);
            assertThat(event.get("details").get("roleSource").asString()).isEqualTo("APP");
            assertThat(event.get("details").get("count").asInt()).isPositive();
        });
        assertThat(admin.patch("/api/companies/" + companyId + "/members/" + userId, Map.of("role", "VIEWER"))
            .status()).isEqualTo(200);
    }

    /** The provider takes the role back at the next sign-in while it decides (ADR-0088 decision 7, to IDP). */
    private String roleAfterSignIn(String subject, String email, List<String> groups) {
        ApiClient browser = new ApiClient(PORT);
        ApiResponse callback = login(browser, ROLES, groups(subject, email, groups), false);
        assertThat(callback.header("Location")).isEqualTo("http://localhost:" + PORT + "/");
        return browser.get("/api/me").json().get("memberships").get(0).get("role").asString();
    }

    private static Identity groups(String subject, String email, List<String> groups) {
        return new Identity(subject, Map.of("email", email, "email_verified", true, "name", "Role Person", "groups",
                groups));
    }

    private static JsonNode member(ApiClient admin, String companyId, String userId) {
        for (JsonNode member : admin.get("/api/companies/" + companyId + "/members?limit=200").json().get("items")) {
            if (member.get("userId").asString().equals(userId)) {
                return member;
            }
        }
        throw new AssertionError("no member " + userId);
    }

    private static JsonNode adminEvents() {
        ApiClient admin = new ApiClient(PORT);
        assertThat(admin.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);
        return admin.get("/api/admin/audit-events?limit=20").json().get("items");
    }

    /** The newest {@code auth.login.failed} of a registration; the events come newest first. */
    private static JsonNode latestFailedLogin(String registrationId) {
        for (JsonNode event : adminEvents()) {
            if (event.get("action").asString().equals("auth.login.failed")
                    && registrationId.equals(event.path("details").path("provider").asString(""))) {
                return event;
            }
        }
        throw new AssertionError("no auth.login.failed for " + registrationId);
    }

    private static JsonNode event(JsonNode events, String action, String targetId) {
        for (JsonNode event : events) {
            if (event.get("action").asString().equals(action) && targetId.equals(event.path("targetId").asString(""))) {
                return event;
            }
        }
        throw new AssertionError("no " + action + " for " + targetId + " in " + events);
    }
}
