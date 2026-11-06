package com.flowtivo.tinfirm.ee.scim;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;

import tools.jackson.databind.JsonNode;

/**
 * ADR-0087, decision 11: the SCIM endpoint is not company-scoped by its path, so the token alone names the company. A
 * token of one company never reads, finds, changes or removes another company's members, and a user who is a member
 * of both is seen by each as that company's member only.
 */
class ScimTenantIsolationTest extends ScimTest {

    @Test
    void aTokenOfOneCompanyNeverReachesAnothersMembers() {
        TestCompany mine = scimCompany("scim");
        Scim scim = scim(mine);
        TestCompany theirs = companyWithRoles();
        UUID theirMember = theirs.userId("MEMBER");
        String theirPath = "/Users/" + theirMember;

        assertThat(scim.get(theirPath).status()).isEqualTo(404);
        assertThat(scim.patch(theirPath, patch(op("replace", "active", false))).status()).isEqualTo(404);
        assertThat(scim.put(theirPath, user(theirs.email("MEMBER"), "x-" + UUID.randomUUID())).status())
            .isEqualTo(404);
        assertThat(scim.delete(theirPath).status()).isEqualTo(404);
        JsonNode filtered = scim.get("/Users?filter=" + ScimIntegrationTest.encode(
                "userName eq \"" + theirs.email("MEMBER") + "\"")).json();
        assertThat(filtered.get("totalResults").asInt()).isZero();
        JsonNode all = scim.get("/Users?count=100").json();
        assertThat(all.get("Resources")).noneSatisfy(
                user -> assertThat(user.get("id").asString()).isEqualTo(theirMember.toString()));
        // Nothing of theirs changed.
        assertThat(loginAs(theirs.email("OWNER")).get(theirs.path("/members?limit=100")).json().get("items"))
            .anySatisfy(member -> {
                assertThat(member.get("userId").asString()).isEqualTo(theirMember.toString());
                assertThat(member.get("suspended").asBoolean()).isFalse();
            });
    }

    @Test
    void aPersonInTwoCompaniesIsEachCompanysOwnMember() {
        TestCompany mine = scimCompany("scim");
        TestCompany theirs = companyWithRoles();
        Scim scim = scim(mine);
        String email = theirs.email("MEMBER");
        UUID shared = theirs.userId("MEMBER");

        // Their member becomes ours too, as an OWNER adds a user by email.
        assertThat(id(scim.post("/Users", user(email, "ext-" + UUID.randomUUID())))).isEqualTo(shared);
        scim.patch("/Users/" + shared, patch(op("replace", "active", false)));

        // Suspended in ours, not in theirs, and the user stays active: another company holds them.
        memberIs(theirs, shared, false);
        assertThat(loginAs(email).get(theirs.path("/agents")).status()).isEqualTo(200);
        // Neither is renamed by the other company's IdP.
        JsonNode renamed = scim.patch("/Users/" + shared, patch(op("replace", "displayName", "Taken Over"))).json();
        assertThat(renamed.get("displayName").asString()).isNotEqualTo("Taken Over");
        // A token of another SCIM company sees the person only once they are its member.
        TestCompany third = scimCompany("scimapp");
        assertThat(scim(third).get("/Users/" + shared).status()).isEqualTo(404);
    }

    /**
     * L5 of the security review of 2026-10-27: a company's SCIM deactivates on the instance only a user it created.
     * Another company's user it adopted by email loses this company's membership, never their account, even when it
     * holds the last membership in effect.
     */
    @Test
    void deactivatingAnAdoptedUserSuspendsTheMembershipOnly() {
        TestCompany theirs = scimCompany("scimapp");
        TestCompany mine = scimCompany("scim");
        Scim their = scim(theirs);
        Scim scim = scim(mine);
        String email = uniqueEmail("adopted");
        UUID person = UUID.fromString(their.create(user(email, "theirs-" + UUID.randomUUID())));
        assertThat(id(scim.post("/Users", user(email, "mine-" + UUID.randomUUID())))).isEqualTo(person);
        // Their own IdP suspends them in their company: ours holds the last membership in effect.
        assertThat(their.patch("/Users/" + person, patch(op("replace", "active", false))).status()).isEqualTo(200);
        assertThat(ScimIntegrationTest.userActive(person)).isTrue();

        assertThat(scim.patch("/Users/" + person, patch(op("replace", "active", false))).status()).isEqualTo(200);
        assertThat(scim.delete("/Users/" + person).status()).isEqualTo(204);

        assertThat(ScimIntegrationTest.userActive(person)).as("our IdP never deactivates their user").isTrue();
        // Their IdP lifts its own suspension, and the person is theirs again, untouched by ours.
        ApiResponse back = their.patch("/Users/" + person, patch(op("replace", "active", true)));
        assertThat(back.json().get("active").asBoolean()).as("%s", back).isTrue();
    }

    /** A member's standing as their company's OWNER reads it. */
    private void memberIs(TestCompany company, UUID userId, boolean suspended) {
        JsonNode items = loginAs(company.email("OWNER")).get(company.path("/members?limit=100")).json().get("items");
        assertThat(items).anySatisfy(member -> {
            assertThat(member.get("userId").asString()).isEqualTo(userId.toString());
            assertThat(member.get("suspended").asBoolean()).isEqualTo(suspended);
        });
    }
}
