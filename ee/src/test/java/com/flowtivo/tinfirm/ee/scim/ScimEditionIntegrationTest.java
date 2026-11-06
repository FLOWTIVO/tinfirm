package com.flowtivo.tinfirm.ee.scim;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;

/**
 * ADR-0087, decision 10, and ADR-0077, decision 7: SCIM serves while the licence holds {@code SCIM}, Enterprise in
 * force and in its grace; under Community, Pro, or after an Enterprise key expired, every request with a valid token
 * is {@code 403 EDITION_REQUIRED} and no token is issued, while the tokens stay listed and revocable and nothing SCIM
 * made is undone.
 */
class ScimEditionIntegrationTest extends ScimTest {

    @AfterEach
    void enterpriseAgain() {
        LocalDate today = TestLicences.today();
        TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.plusYears(1));
    }

    @Test
    void communityAndProServeNoScimAndStillRevokeTokens() {
        TestCompany company = scimCompany();
        String token = issueToken(company);
        Scim scim = scim(token);
        String id = scim.create(user(uniqueEmail("kept"), "ext-" + UUID.randomUUID()));
        ApiClient owner = loginAs(company.email("OWNER"));

        for (String edition : new String[] { "COMMUNITY", "PRO" }) {
            if (edition.equals("COMMUNITY")) {
                TestLicences.clear();
            }
            else {
                LocalDate today = TestLicences.today();
                TestLicences.hold("PRO", 100_000, today.minusMonths(1), today.plusYears(1));
            }
            editionRequired(scim.get("/Users"));
            editionRequired(scim.get("/ServiceProviderConfig"));
            editionRequired(scim.patch("/Users/" + id, patch(op("replace", "active", false))));
            ApiResponse issue = owner.post(company.path("/scim/tokens"), Map.of("name", "next"));
            assertThat(issue.status()).as("%s %s", edition, issue).isEqualTo(403);
            assertThat(issue.problemCode()).isEqualTo("EDITION_REQUIRED");
            assertThat(owner.get(company.path("/scim")).json().get("licensed").asBoolean()).isFalse();
            assertThat(owner.get(company.path("/scim/tokens")).json().get("items")).hasSize(1);
            // The member SCIM made stays.
            assertThat(owner.get(company.path("/members?limit=100")).json().get("items")).anySatisfy(
                    member -> assertThat(member.get("userId").asString()).isEqualTo(id));
        }
        UUID tokenId = UUID.fromString(owner.get(company.path("/scim/tokens")).json().get("items").get(0).get("id")
            .asString());
        assertThat(owner.delete(company.path("/scim/tokens/" + tokenId)).status()).isEqualTo(204);
        enterpriseAgain();
        assertThat(scim.get("/Users").status()).as("revoked under Community, refused after").isEqualTo(401);
    }

    @Test
    void enterpriseServesInItsGraceAndStopsAfterIt() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        LocalDate today = TestLicences.today();

        TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.minusDays(10));
        assertThat(scim.get("/Users").status()).as("GRACE").isEqualTo(200);

        TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.minusDays(40));
        editionRequired(scim.get("/Users"));

        enterpriseAgain();
        assertThat(scim.get("/Users").status()).as("a new key restores it at once").isEqualTo(200);
    }

    private static void editionRequired(ApiResponse response) {
        assertThat(response.status()).as("%s", response).isEqualTo(403);
        assertThat(response.json().get("detail").asString()).isEqualTo("EDITION_REQUIRED");
        assertThat(response.json().get("schemas").get(0).asString())
            .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
    }
}
