package com.flowtivo.tinfirm.identity.internal.saml;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;

/**
 * ADR-0086, decision 14, and ADR-0077, decisions 4 and 7: SAML signs people in while the licence holds {@code SAML},
 * Enterprise in force, in its grace and after it expired, so that a company that bought SAML is never locked out;
 * under Community or Pro the start and the ACS end on {@code EDITION_REQUIRED}, audited {@code edition_required}, and
 * a new Enterprise key restores it at once.
 */
class SamlEditionIntegrationTest extends SamlIntegrationTest {

    private static final String EDITION_REQUIRED = BASE + "/login?error=EDITION_REQUIRED";

    @AfterEach
    void enterpriseAgain() {
        LocalDate today = TestLicences.today();
        TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.plusYears(1));
    }

    @Test
    void communityAndProRefuseTheStartAndTheAcs() {
        for (String edition : new String[] { "COMMUNITY", "PRO" }) {
            if (edition.equals("COMMUNITY")) {
                TestLicences.clear();
            }
            else {
                LocalDate today = TestLicences.today();
                TestLicences.hold("PRO", 100_000, today.minusMonths(1), today.plusYears(1));
            }
            ApiClient browser = new ApiClient(PORT);
            ApiResponse start = browser.get("/saml2/authenticate/stub");
            assertThat(start.header("Location")).as(edition).isEqualTo(EDITION_REQUIRED);
            assertThat(browser.get("/saml2/authenti%63ate/stub").header("Location")).as("%s, encoded", edition)
                .isEqualTo(EDITION_REQUIRED);
            assertThat(latestFailedLogin("stub").get("details").get("reason").asString())
                .isEqualTo("edition_required");

            SamlTestIdp.ResponseBuilder response = STUB.respond(spEntityId("stub"), acs("stub"), "_any");
            response.attribute("email", "community-" + UUID.randomUUID() + "@example.com");
            assertThat(post(browser, "stub", response.encoded()).header("Location")).as(edition)
                .isEqualTo(EDITION_REQUIRED);
            assertThat(browser.get("/api/me").status()).isEqualTo(401);
        }

        enterpriseAgain();
        assertThat(signIn().header("Location")).as("a new key restores it at once").isEqualTo(BASE + "/");
    }

    @Test
    void enterpriseSignsInInItsGraceAndAfterItExpired() {
        LocalDate today = TestLicences.today();

        TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.minusDays(10));
        assertThat(signIn().header("Location")).as("GRACE").isEqualTo(BASE + "/");

        TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.minusDays(40));
        assertThat(signIn().header("Location")).as("EXPIRED").isEqualTo(BASE + "/");
    }

    private static ApiResponse signIn() {
        ApiClient browser = new ApiClient(PORT);
        Started started = start(browser, "stub");
        SamlTestIdp.ResponseBuilder response = STUB.respond(spEntityId("stub"), acs("stub"), started.requestId());
        response.attribute("email", "edition-" + UUID.randomUUID() + "@example.com");
        return post(browser, "stub", response.encoded());
    }
}
