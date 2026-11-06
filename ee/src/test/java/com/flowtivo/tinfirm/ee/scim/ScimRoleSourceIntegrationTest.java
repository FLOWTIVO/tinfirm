package com.flowtivo.tinfirm.ee.scim;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import com.flowtivo.tinfirm.ee.licence.TestLicences;
import com.flowtivo.tinfirm.shared.licence.LicenceChanged;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;

import tools.jackson.databind.JsonNode;

/**
 * ADR-0088, decisions 7 and 8, for SCIM: under {@code IDP} SCIM owns the role of the memberships it manages, and the
 * app answers {@code 409 ROLE_MANAGED} ("managed by SCIM"), but may make the member OWNER; under {@code APP} the role
 * is the app's after creation; once the licence stops serving SCIM, its roles are released to the app at once.
 */
class ScimRoleSourceIntegrationTest extends ScimTest {

    @Autowired
    ApplicationEventPublisher events;

    @AfterEach
    void enterpriseAgain() {
        LocalDate today = TestLicences.today();
        TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.plusYears(1));
    }

    @Test
    void underTheIdpTheAppCannotChangeARoleScimDecides() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String id = scim.create(user(uniqueEmail("managed"), "ext-" + UUID.randomUUID(), "member"));
        ApiClient owner = loginAs(company.email("OWNER"));

        JsonNode member = member(owner, company, id);
        assertThat(member.get("roleManagedBy").get("source").asString()).isEqualTo("SCIM");
        ApiResponse changed = owner.patch(company.path("/members/" + id), Map.of("role", "ADMIN"));
        assertThat(changed.status()).as("%s", changed).isEqualTo(409);
        assertThat(changed.problemCode()).isEqualTo("ROLE_MANAGED");
        assertThat(changed.json().get("details").get("provider").asString()).isEqualTo("scim");

        // An owner is never SCIM's: made OWNER in the app, the role is the app's, and SCIM's role is ignored for them.
        assertThat(owner.patch(company.path("/members/" + id), Map.of("role", "OWNER")).status()).isEqualTo(200);
        assertThat(member(owner, company, id).path("roleManagedBy").isNull()
                || member(owner, company, id).path("roleManagedBy").isMissingNode()).isTrue();
        ApiResponse ignored = scim.patch("/Users/" + id, patch(op("replace", EXTENSION + ":role", "viewer")));
        assertThat(ignored.json().get(EXTENSION).get("role").asString()).isEqualTo("owner");
    }

    @Test
    void scimTakesTheRoleOfAMemberTheAppAdded() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        UUID member = company.userId("MEMBER");
        ApiClient owner = loginAs(company.email("OWNER"));
        assertThat(member(owner, company, member.toString()).path("roleManagedBy").isNull()
                || member(owner, company, member.toString()).path("roleManagedBy").isMissingNode()).isTrue();

        // A request without the role leaves it, and the app's.
        scim.patch("/Users/" + member, patch(op("replace", "displayName", "Still the app's")));
        assertThat(owner.patch(company.path("/members/" + member), Map.of("role", "VIEWER")).status()).isEqualTo(200);

        scim.patch("/Users/" + member, patch(op("replace", EXTENSION + ":role", "member")));
        assertThat(owner.patch(company.path("/members/" + member), Map.of("role", "ADMIN")).problemCode())
            .isEqualTo("ROLE_MANAGED");
    }

    @Test
    void underTheAppScimGivesARoleOnceAndTheAppDecidesAfter() {
        TestCompany company = scimCompany("scimapp");
        Scim scim = scim(company);
        String id = scim.create(user(uniqueEmail("appdecides"), "ext-" + UUID.randomUUID(), "member"));
        ApiClient owner = loginAs(company.email("OWNER"));

        assertThat(member(owner, company, id).get("role").asString()).isEqualTo("MEMBER");
        assertThat(owner.patch(company.path("/members/" + id), Map.of("role", "ADMIN")).status()).isEqualTo(200);
        assertThat(scim.patch("/Users/" + id, patch(op("replace", EXTENSION + ":role", "viewer"))).json()
            .get(EXTENSION).get("role").asString()).isEqualTo("admin");
    }

    @Test
    void theLicenceStoppingScimReleasesItsRolesAtOnce() {
        TestCompany company = scimCompany();
        Scim scim = scim(company);
        String id = scim.create(user(uniqueEmail("released"), "ext-" + UUID.randomUUID(), "member"));
        ApiClient owner = loginAs(company.email("OWNER"));
        LocalDate today = TestLicences.today();

        TestLicences.hold("ENTERPRISE", null, today.minusYears(1), today.minusDays(40));
        events.publishEvent(new LicenceChanged());

        assertThat(member(owner, company, id).path("roleManagedBy").isNull()
                || member(owner, company, id).path("roleManagedBy").isMissingNode()).isTrue();
        assertThat(owner.patch(company.path("/members/" + id), Map.of("role", "ADMIN")).status()).isEqualTo(200);
        assertThat(auditEvents(owner, company, "member.role_source.changed")).anySatisfy(event -> {
            assertThat(event.get("details").get("previousRoleSource").asString()).isEqualTo("SCIM");
            assertThat(event.get("details").get("count").asInt()).isEqualTo(1);
        });

        // A key that serves SCIM again: SCIM takes the role back at its next write that carries one.
        enterpriseAgain();
        scim.patch("/Users/" + id, patch(op("replace", EXTENSION + ":role", "member")));
        assertThat(member(owner, company, id).get("roleManagedBy").get("source").asString()).isEqualTo("SCIM");
    }

    private static JsonNode member(ApiClient reader, TestCompany company, String userId) {
        for (JsonNode member : reader.get(company.path("/members?limit=100")).json().get("items")) {
            if (member.get("userId").asString().equals(userId)) {
                return member;
            }
        }
        throw new AssertionError("Not a member: " + userId);
    }
}
