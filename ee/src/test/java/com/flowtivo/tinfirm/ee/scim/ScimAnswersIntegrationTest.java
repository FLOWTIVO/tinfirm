package com.flowtivo.tinfirm.ee.scim;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.web.ServerBusy;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.ConnectionWatch;

/**
 * SCIM answers, in its own form, what the API answers (shared.web.ServerBusy): a request that finds no free database
 * connection before it saved anything is a 503 with {@code Retry-After}; one whose change may already be saved when
 * a step after it failed is a 500 that says so, without {@code Retry-After}.
 */
class ScimAnswersIntegrationTest extends ScimTest {

    private static final String SESSIONS = "com.flowtivo.tinfirm.identity.internal.UserSessions";

    private static final String FILTER = "com.flowtivo.tinfirm.company.internal.ScimRequestFilter";

    /**
     * A user's creation whose own transaction finds no free connection: the token's lookup, the check that SCIM is
     * allowed and the token's last use committed before it, and are no change of the request.
     */
    @Test
    void aScimCreationThatFindsNoFreeConnectionForItsOwnTransactionIsToldTheServerIsBusy() throws Exception {
        Scim scim = scim(scimCompany());
        String email = uniqueEmail("busy");

        ApiResponse created;
        try (AutoCloseable busy = ConnectionWatch
            .timeOutAt("com.flowtivo.tinfirm.company.internal.ScimController$$SpringCGLIB$$0.createScimUser")) {
            created = scim.post("/Users", user(email, "ext-" + java.util.UUID.randomUUID()));
        }

        assertBusy(created);
        assertThat(scim.get("/Users?filter=" + java.net.URLEncoder.encode("userName eq \"" + email + "\"",
                java.nio.charset.StandardCharsets.UTF_8)).json().get("totalResults").asInt()).isZero();
    }

    /**
     * The token's last use is SCIM's bookkeeping: when it finds no free connection, nothing of the request is saved
     * yet.
     */
    @Test
    void aTokensLastUseThatFindsNoFreeConnectionIsToldTheServerIsBusy() throws Exception {
        Scim scim = scim(scimCompany());
        String email = uniqueEmail("untouched");

        ApiResponse created;
        try (AutoCloseable busy = ConnectionWatch.timeOutAt(FILTER + ".doFilterInternal", FILTER + ".enabled",
                "com.flowtivo.tinfirm.company.internal.JdbcScimTokenStore")) {
            created = scim.post("/Users", user(email, "ext-" + java.util.UUID.randomUUID()));
        }

        assertBusy(created);
        assertThat(scim.get("/Users?filter=" + java.net.URLEncoder.encode("userName eq \"" + email + "\"",
                java.nio.charset.StandardCharsets.UTF_8)).json().get("totalResults").asInt()).isZero();
    }

    /** The token's lookup, before anything of SCIM's chain, finds no free connection: answered in SCIM's form too. */
    @Test
    void aTokensLookupThatFindsNoFreeConnectionIsAnsweredInScimsForm() throws Exception {
        Scim scim = scim(scimCompany());

        ApiResponse users;
        try (AutoCloseable busy = ConnectionWatch
            .timeOutAt("com.flowtivo.tinfirm.company.internal.JdbcScimTokenStore")) {
            users = scim.get("/Users");
        }

        assertBusy(users);
    }

    private static void assertBusy(ApiResponse answer) {
        assertThat(answer.status()).as("%s", answer).isEqualTo(503);
        assertThat(answer.contentType()).startsWith("application/scim+json");
        assertThat(answer.json().get("status").asString()).isEqualTo("503");
        assertThat(answer.json().get("detail").asString()).doesNotContain("saved");
        assertThat(answer.header("Retry-After")).isEqualTo("2");
    }

    @Test
    void aScimRequestThatFindsNoFreeConnectionIsToldTheServerIsBusy() throws Exception {
        Scim scim = scim(scimCompany());

        ApiResponse users;
        try (AutoCloseable busy = ConnectionWatch.timeOutAt("com.flowtivo.tinfirm.company.internal.ScimController")) {
            users = scim.get("/Users");
        }

        assertThat(users.status()).as("%s", users).isEqualTo(503);
        assertThat(users.contentType()).startsWith("application/scim+json");
        assertThat(users.json().get("status").asString()).isEqualTo("503");
        assertThat(users.header("Retry-After")).isEqualTo("2");
    }

    /**
     * A user SCIM provisioned and then deletes: the deletion commits, and the deletion of the user's sessions after it
     * finds no free connection.
     */
    @Test
    void aScimChangeSavedBeforeAStepAfterItFailedIsToldItMayAlreadyBeSaved() throws Exception {
        Scim scim = scim(scimCompany());
        String member = scim.create(user(uniqueEmail("provisioned"), "ext-" + java.util.UUID.randomUUID()));

        ApiResponse removed;
        try (AutoCloseable busy = ConnectionWatch.timeOutKeptWorkAt(SESSIONS)) {
            removed = scim.delete("/Users/" + member);
        }

        assertThat(removed.status()).as("%s", removed).isEqualTo(500);
        assertThat(removed.contentType()).startsWith("application/scim+json");
        assertThat(removed.json().get("detail").asString()).isEqualTo(ServerBusy.SAVED_THEN_FAILED);
        assertThat(removed.header("Retry-After")).isNull();
        assertThat(scim.get("/Users/" + member).status()).isEqualTo(404);
    }
}
