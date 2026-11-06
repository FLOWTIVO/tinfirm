package com.flowtivo.tinfirm.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

class AuthIntegrationTest extends IntegrationTest {

    @Test
    void providersListsLoginMethodsAndSetsTheCsrfCookie() {
        ApiClient browser = client();
        ApiResponse response = browser.get("/api/auth/providers");

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.json().get("localEnabled").asBoolean()).isTrue();
        JsonNode provider = response.json().get("providers").get(0);
        assertThat(provider.get("id").asString()).isEqualTo("test-idp");
        assertThat(provider.get("name").asString()).isEqualTo("Test IdP");
        assertThat(provider.get("kind").asString()).isEqualTo("OIDC");
        assertThat(provider.get("loginUrl").asString()).isEqualTo("/oauth2/authorization/test-idp");
        assertThat(response.setCookies()).anySatisfy(cookie -> assertThat(cookie).startsWith("XSRF-TOKEN=")
            .doesNotContainIgnoringCase("HttpOnly")
            .contains("SameSite=Lax"));
        assertThat(browser.cookie("XSRF-TOKEN")).isPresent();
    }

    @Test
    void anonymousApiCallsGetJson401WithoutRedirect() {
        ApiResponse response = client().get("/api/me");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.contentType()).startsWith("application/problem+json");
        assertThat(response.json().get("status").asInt()).isEqualTo(401);
        assertThat(response.header("Location")).isNull();
    }

    @Test
    void localLoginStartsAServerSideSession() {
        ApiClient browser = client();
        ApiResponse login = browser.login(ADMIN_EMAIL, ADMIN_PASSWORD);

        assertThat(login.status()).isEqualTo(200);
        assertThat(login.json().get("email").asString()).isEqualTo(ADMIN_EMAIL);
        assertThat(login.json().get("instanceAdmin").asBoolean()).isTrue();
        assertThat(login.json().get("authSource").asString()).isEqualTo("LOCAL");
        assertThat(login.json().get("memberships")).isNotEmpty();
        assertThat(login.setCookies()).anySatisfy(cookie -> assertThat(cookie).startsWith("TINFIRM_SESSION=")
            .contains("HttpOnly")
            .contains("SameSite=Lax")
            .doesNotContain("Secure"));

        ApiResponse me = browser.get("/api/me");
        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().get("email").asString()).isEqualTo(ADMIN_EMAIL);
    }

    @Test
    void loginRotatesTheSessionIdAndTheCsrfToken() {
        ApiClient browser = client();
        browser.fetchCsrfToken();
        // Starting an OIDC login creates an anonymous session.
        assertThat(browser.get("/oauth2/authorization/test-idp").status()).isEqualTo(302);
        String anonymousSession = browser.cookie("TINFIRM_SESSION").orElseThrow().getValue();
        String oldCsrf = browser.cookie("XSRF-TOKEN").orElseThrow().getValue();

        assertThat(browser.login(ADMIN_EMAIL, ADMIN_PASSWORD).status()).isEqualTo(200);

        assertThat(browser.cookie("TINFIRM_SESSION").orElseThrow().getValue()).isNotEqualTo(anonymousSession);
        String newCsrf = browser.cookie("XSRF-TOKEN").orElseThrow().getValue();
        assertThat(newCsrf).isNotEqualTo(oldCsrf);

        // The SPA's next mutating call uses the token from the new cookie and succeeds...
        ApiResponse create = browser.post("/api/admin/users", newUser(uniqueEmail("csrf")));
        assertThat(create.status()).isEqualTo(201);
        // ...while the pre-login token is no longer accepted.
        ApiResponse stale = browser.send("POST", "/api/admin/users", newUser(uniqueEmail("csrf")),
                Map.of("X-XSRF-TOKEN", oldCsrf));
        assertThat(stale.status()).isEqualTo(403);
    }

    @Test
    void everyMutatingRequestNeedsTheCsrfToken() {
        ApiClient noHeader = client().withoutCsrfHeader();
        noHeader.fetchCsrfToken();
        assertThat(noHeader.post("/api/auth/login", Map.of("email", ADMIN_EMAIL, "password", ADMIN_PASSWORD))
            .status()).isEqualTo(403);

        ApiClient admin = admin();
        ApiResponse forged = admin.send("POST", "/api/admin/users", newUser(uniqueEmail("forged")),
                Map.of("X-XSRF-TOKEN", "forged"));
        assertThat(forged.status()).isEqualTo(403);
        assertThat(forged.contentType()).startsWith("application/problem+json");
        assertThat(admin.withoutCsrfHeader().post("/api/auth/logout", null).status()).isEqualTo(403);
    }

    @Test
    void wrongPasswordAndUnknownEmailFailAlikeAndAreAudited() {
        ApiClient admin = admin();
        String email = uniqueEmail("wrongpw");
        UUID userId = createUser(admin, email, false);

        ApiResponse wrong = client().login(email, "not-the-password");
        ApiResponse unknown = client().login(uniqueEmail("nobody"), "whatever-password");

        assertThat(wrong.status()).isEqualTo(401);
        assertThat(unknown.status()).isEqualTo(401);
        assertThat(wrong.json().get("detail").asString()).isEqualTo(unknown.json().get("detail").asString());

        JsonNode events = admin.get("/api/admin/audit-events?limit=50").json().get("items");
        JsonNode badCredentials = find(events, "auth.login.failed", userId.toString());
        assertThat(badCredentials.get("details").get("reason").asString()).isEqualTo("bad_credentials");
        JsonNode unknownUser = StreamSupport.stream(events.spliterator(), false)
            .filter(event -> event.get("action").asString().equals("auth.login.failed"))
            .filter(event -> event.get("details").path("reason").asString("").equals("unknown_user"))
            .findFirst()
            .orElseThrow();
        // No email, no target: just the reason.
        assertThat(unknownUser.get("details").toString()).isEqualTo("{\"reason\":\"unknown_user\"}");
        assertThat(unknownUser.get("actor").get("display").asString()).isEqualTo("anonymous");
    }

    @Test
    void loginIsThrottledPerEmail() {
        String email = uniqueEmail("throttled");
        createUser(admin(), email, false);
        ApiClient attacker = client();
        for (int attempt = 0; attempt < 5; attempt++) {
            attacker.withClientIp(ApiClient.randomIp());
            assertThat(attacker.login(email, "wrong-password-" + attempt).status()).isEqualTo(401);
        }
        attacker.withClientIp(ApiClient.randomIp());
        ApiResponse throttled = attacker.login(email, PASSWORD);

        assertThat(throttled.status()).isEqualTo(429);
        assertThat(throttled.problemCode()).isEqualTo("RATE_LIMITED");
        assertThat(throttled.header("Retry-After")).isNotBlank();
    }

    @Test
    void loginIsThrottledPerClientIp() {
        ApiClient attacker = client().withClientIp(ApiClient.randomIp());
        for (int attempt = 0; attempt < 30; attempt++) {
            assertThat(attacker.login(uniqueEmail("spray"), "whatever-password").status()).isEqualTo(401);
        }
        ApiResponse throttled = attacker.login(uniqueEmail("spray"), "whatever-password");

        assertThat(throttled.status()).isEqualTo(429);
        assertThat(throttled.problemCode()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void logoutDeletesTheSessionAndClearsTheCookies() {
        ApiClient browser = admin();
        String session = browser.cookie("TINFIRM_SESSION").orElseThrow().getValue();

        ApiResponse logout = browser.post("/api/auth/logout", null);

        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.setCookies()).anySatisfy(cookie -> assertCleared(cookie, "TINFIRM_SESSION"));
        assertThat(logout.setCookies()).anySatisfy(cookie -> assertCleared(cookie, "XSRF-TOKEN"));
        // The old session cookie is worthless: the session is gone from the database.
        ApiClient replay = client();
        replay.setCookie("TINFIRM_SESSION", session);
        assertThat(replay.get("/api/me").status()).isEqualTo(401);
    }

    @Test
    void deactivatedUsersLoseTheirSessionsAndCannotLogIn() {
        ApiClient admin = admin();
        String email = uniqueEmail("deactivated");
        UUID userId = createUser(admin, email, false);
        ApiClient user = loginAs(email);
        assertThat(user.get("/api/me").status()).isEqualTo(200);

        assertThat(admin.patch("/api/admin/users/" + userId, Map.of("active", false)).status()).isEqualTo(200);

        assertThat(user.get("/api/me").status()).isEqualTo(401);
        assertThat(client().login(email, PASSWORD).status()).isEqualTo(401);
    }

    @Test
    void securityHeadersAreSentOnEveryResponse() {
        for (ApiResponse response : new ApiResponse[] { client().get("/api/system/info"), client().get("/api/me"),
                client().get("/c/some/route") }) {
            assertThat(response.header("Content-Security-Policy")).isEqualTo("default-src 'self'; script-src 'self'; "
                    + "style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self' data:; "
                    + "connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; form-action 'self'; "
                    + "object-src 'none'");
            assertThat(response.header("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.header("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
            // TINFIRM_PUBLIC_URL is http in tests.
            assertThat(response.header("Strict-Transport-Security")).isNull();
        }
    }

    @Test
    void securityHeadersAreSentByEveryFilterChain() {
        // L4: the chains without a session (webhooks, chats, the Gmail add-on, SCIM, the runner transport) write the
        // same headers as the session chain, the MCP endpoint's and the authorization server's, whatever the answer.
        for (String path : new String[] { "/api/hooks/none", "/api/chat/none", "/api/gmail/none", "/scim/v2/Users",
                "/api/runner/v1/none", "/api/companies/00000000-0000-0000-0000-000000000000/mcp", "/oauth2/jwks" }) {
            ApiResponse response = client().get(path);
            assertThat(response.header("Content-Security-Policy")).as(path).isEqualTo("default-src 'self'; "
                    + "script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; "
                    + "font-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; "
                    + "form-action 'self'; object-src 'none'");
            assertThat(response.header("X-Frame-Options")).as(path).isEqualTo("DENY");
            assertThat(response.header("X-Content-Type-Options")).as(path).isEqualTo("nosniff");
            assertThat(response.header("Referrer-Policy")).as(path).isEqualTo("strict-origin-when-cross-origin");
        }
    }

    @Test
    void theOutlookAddInsPagesLoadOfficeJsAndMayBeFramedByOutlook() {
        // ADR-0074: the task pane and the sign-in callback load Office.js from Microsoft's CDN, and Outlook on the web
        // frames the task pane.
        for (ApiResponse response : new ApiResponse[] { client().get("/outlook/" + UUID.randomUUID()),
                client().get("/outlook/callback?code=c&state=s") }) {
            assertThat(response.header("Content-Security-Policy")).isEqualTo("default-src 'self'; "
                    + "script-src 'self' https://appsforoffice.microsoft.com/lib/1/hosted/ "
                    + "https://ajax.aspnetcdn.com/ajax/3.5/MicrosoftAjax.js; "
                    + "style-src 'self' 'unsafe-inline'; "
                    + "img-src 'self' data:; font-src 'self' data:; "
                    + "connect-src 'self' https://appsforoffice.microsoft.com/lib/1/hosted/; frame-ancestors 'self' "
                    + "https://outlook.office.com https://outlook.office365.com https://outlook.live.com "
                    + "https://outlook.cloud.microsoft; base-uri 'self'; form-action 'self'; object-src 'none'");
            assertThat(response.header("X-Frame-Options")).isNull();
            assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.header("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");
        }
        // A path that only begins like them is any other page.
        ApiResponse other = client().get("/outlookish");
        assertThat(other.header("X-Frame-Options")).isEqualTo("DENY");
        assertThat(other.header("Content-Security-Policy")).contains("frame-ancestors 'none'");
    }

    @Test
    void oidcLoginUsesPkceAndThePublicRedirectUri() {
        ApiResponse redirect = client().get("/oauth2/authorization/test-idp");

        assertThat(redirect.status()).isEqualTo(302);
        String location = redirect.header("Location");
        assertThat(location).startsWith("http://idp.test/authorize?")
            .contains("code_challenge=")
            .contains("code_challenge_method=S256")
            .contains("redirect_uri=http://localhost:8080/login/oauth2/code/test-idp")
            .contains("scope=openid");
    }

    private static void assertCleared(String setCookie, String name) {
        assertThat(setCookie).startsWith(name + "=;");
        assertThat(setCookie.contains("Max-Age=0") || setCookie.contains("Jan 1970")).as(setCookie).isTrue();
    }

    /** An instance admin's request to create a user: a mutating request that needs the CSRF token. */
    private static Map<String, Object> newUser(String email) {
        return Map.of("email", email, "displayName", "User " + email, "password", PASSWORD, "instanceAdmin", false);
    }

    private static JsonNode find(JsonNode events, String action, String targetId) {
        return StreamSupport.stream(events.spliterator(), false)
            .filter(event -> event.get("action").asString().equals(action))
            .filter(event -> targetId.equals(event.path("targetId").asString(null)))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no " + action + " for " + targetId + " in " + events));
    }
}
