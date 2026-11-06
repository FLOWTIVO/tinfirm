package com.flowtivo.tinfirm.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

@ExtendWith(EnterpriseLicence.class)
class MemberIntegrationTest extends IntegrationTest {

    @Test
    void everyMemberListsMembersWithPagination() {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));

        Set<String> emails = new HashSet<>();
        ApiResponse first = member.get(company.path("/members?limit=2"));
        assertThat(first.status()).isEqualTo(200);
        first.json().get("items").forEach(item -> emails.add(item.get("email").asString()));
        String cursor = first.json().get("nextCursor").asString();
        ApiResponse second = member.get(company.path("/members?limit=2&cursor=" + cursor));
        second.json().get("items").forEach(item -> emails.add(item.get("email").asString()));

        assertThat(emails).containsExactlyInAnyOrderElementsOf(company.emails().values());
        assertThat(second.json().has("nextCursor")).isFalse();
        JsonNode listed = first.json().get("items").get(0);
        assertThat(listed.has("displayName")).isTrue();
        assertThat(listed.has("addedAt")).isTrue();

        // Each member's principal, which a member's own budget is set by (the model access of a principal).
        ApiClient owner = loginAs(company.email("OWNER"));
        java.util.Map<String, String> principals = new java.util.HashMap<>();
        for (String page : new String[] { "/members?limit=2", "/members?limit=2&cursor=" + cursor }) {
            owner.get(company.path(page)).json().get("items").forEach(item -> principals.put(item.get("userId")
                .asString(), item.get("principalId").asString()));
        }
        UUID memberPrincipal = memberPrincipal(company, company.userId("MEMBER"));
        assertThat(principals.get(company.userId("MEMBER").toString())).isEqualTo(memberPrincipal.toString());
        assertThat(owner.get(company.path("/principals/" + principals.get(company.userId("MEMBER").toString())
                + "/model-access")).status()).isNotEqualTo(404);
    }

    @Test
    void ownerAddsMembersAndDuplicatesOrUnknownEmailsAreRejected() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String email = uniqueEmail("newbie");
        createUser(admin(), email, false);

        ApiResponse added = owner.post(company.path("/members"), Map.of("email", email.toUpperCase(), "role", "MEMBER"));
        assertThat(added.status()).isEqualTo(201);
        assertThat(added.json().get("email").asString()).isEqualTo(email);
        assertThat(added.json().get("role").asString()).isEqualTo("MEMBER");
        // The principal the membership just made comes with it.
        assertThat(added.json().get("principalId").asString())
            .isEqualTo(memberPrincipal(company, UUID.fromString(added.json().get("userId").asString())).toString());

        ApiResponse duplicate = owner.post(company.path("/members"), Map.of("email", email, "role", "MEMBER"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.problemCode()).isEqualTo("DUPLICATE_MEMBER");

        ApiResponse unknown = owner.post(company.path("/members"),
                Map.of("email", uniqueEmail("ghost"), "role", "MEMBER"));
        assertThat(unknown.status()).isEqualTo(404);
        assertThat(unknown.problemCode()).isEqualTo("USER_NOT_FOUND");

        // The member's principal comes with the membership (the package 1 contract, §3.1).
        JsonNode latest = owner.get(company.path("/audit-events?limit=2")).json().get("items");
        assertThat(latest).extracting(event -> event.get("action").asString())
            .containsExactly("principal.created", "member.added");
        latest.forEach(event -> assertThat(event.get("details").toString()).doesNotContain("@"));
    }

    @Test
    void adminManagesOnlyNonOwnerRoles() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        String email = uniqueEmail("candidate");
        createUser(admin(), email, false);

        assertThat(admin.post(company.path("/members"), Map.of("email", email, "role", "OWNER")).status())
            .isEqualTo(403);
        ApiResponse added = admin.post(company.path("/members"), Map.of("email", email, "role", "MEMBER"));
        assertThat(added.status()).isEqualTo(201);
        String candidate = company.path("/members/" + added.json().get("userId").asString());
        assertThat(admin.patch(company.path("/members/" + company.userId("MEMBER")), Map.of("role", "ADMIN"))
            .status()).isEqualTo(200);
        assertThat(admin.patch(company.path("/members/" + company.userId("MEMBER")), Map.of("role", "OWNER"))
            .status()).isEqualTo(403);
        assertThat(admin.patch(company.path("/members/" + company.userId("OWNER")), Map.of("role", "MEMBER"))
            .status()).isEqualTo(403);
        assertThat(admin.delete(company.path("/members/" + company.userId("OWNER"))).status()).isEqualTo(403);
        assertThat(admin.delete(candidate).status()).isEqualTo(204);
    }

    @Test
    void anInstanceAdminWhoIsAMemberGetsTheirRolesAnswer() {
        // CONVENTIONS §6: the break-glass is for an instance admin who is not a member; a member with a role gets the
        // role's answer, 403 beyond it, and nothing is audited as admin.company.accessed.
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String adminEmail = uniqueEmail("admin-admin");
        createUser(admin(), adminEmail, true);
        addMember(owner, company.id(), adminEmail, "ADMIN");
        String memberEmail = uniqueEmail("admin-member");
        createUser(admin(), memberEmail, true);
        addMember(owner, company.id(), memberEmail, "MEMBER");
        ApiClient instanceAdminAsAdmin = loginAs(adminEmail);
        ApiClient instanceAdminAsMember = loginAs(memberEmail);
        String candidate = uniqueEmail("candidate");
        createUser(admin(), candidate, false);

        ApiResponse promoted = instanceAdminAsAdmin.patch(company.path("/members/" + company.userId("MEMBER")),
                Map.of("role", "OWNER"));
        assertThat(promoted.status()).as("%s", promoted).isEqualTo(403);
        assertThat(instanceAdminAsAdmin.post(company.path("/members"), Map.of("email", candidate, "role", "OWNER"))
            .status()).isEqualTo(403);
        assertThat(instanceAdminAsAdmin.get(company.path("/retention/open-conversations")).status()).isEqualTo(403);

        assertThat(instanceAdminAsMember.get(company.path("/secrets")).status()).isEqualTo(403);
        assertThat(instanceAdminAsMember.post(company.path("/secrets"),
                Map.of("name", "BREAK_GLASS", "value", "v", "description", "d")).status()).isEqualTo(403);
        assertThat(instanceAdminAsMember.get(company.path("/audit-events")).status()).isEqualTo(403);
        assertThat(instanceAdminAsMember.post(company.path("/members"), Map.of("email", candidate, "role", "MEMBER"))
            .status()).isEqualTo(403);
        assertThat(instanceAdminAsMember.post(company.path("/groups"), Map.of("name", "Break glass", "memberIds",
                java.util.List.of())).status()).isEqualTo(403);
        assertThat(instanceAdminAsMember.get(company.path("/grants")).status()).isEqualTo(403);
        assertThat(instanceAdminAsMember.get(company.path("/model-settings")).status()).isEqualTo(403);
        assertThat(instanceAdminAsMember.post(company.path("/service-identities"), Map.of("name", "Break glass"))
            .status()).isEqualTo(403);

        JsonNode roles = owner.get(company.path("/members")).json().get("items");
        roles.forEach(member -> {
            if (member.get("userId").asString().equals(company.userId("MEMBER").toString())) {
                assertThat(member.get("role").asString()).isEqualTo("MEMBER");
            }
        });
        assertThat(auditEvents(owner, company, "admin.company.accessed")).isEmpty();
        assertThat(owner.get(company.path("/secrets")).json().get("items")).isEmpty();
    }

    @Test
    void membersCannotManageMembers() {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        assertThat(member.post(company.path("/members"), Map.of("email", company.email("ADMIN"), "role", "MEMBER"))
            .status()).isEqualTo(403);
        assertThat(member.patch(company.path("/members/" + company.userId("MEMBER")), Map.of("role", "ADMIN"))
            .status()).isEqualTo(403);
        assertThat(member.delete(company.path("/members/" + company.userId("ADMIN"))).status()).isEqualTo(403);
    }

    @Test
    void theLastOwnerCannotBeDemotedOrRemoved() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String ownerPath = company.path("/members/" + company.userId("OWNER"));

        ApiResponse demote = owner.patch(ownerPath, Map.of("role", "ADMIN"));
        assertThat(demote.status()).isEqualTo(409);
        assertThat(demote.problemCode()).isEqualTo("LAST_OWNER");
        ApiResponse remove = owner.delete(ownerPath);
        assertThat(remove.status()).isEqualTo(409);
        assertThat(remove.problemCode()).isEqualTo("LAST_OWNER");

        // With a second owner, the first one may step down.
        assertThat(owner.patch(company.path("/members/" + company.userId("ADMIN")), Map.of("role", "OWNER")).status())
            .isEqualTo(200);
        ApiResponse stepDown = owner.patch(ownerPath, Map.of("role", "MEMBER"));
        assertThat(stepDown.status()).isEqualTo(200);
        assertThat(stepDown.json().get("role").asString()).isEqualTo("MEMBER");

        JsonNode latest = loginAs(company.email("ADMIN")).get(company.path("/audit-events?limit=1")).json()
            .get("items").get(0);
        assertThat(latest.get("action").asString()).isEqualTo("member.role.changed");
        assertThat(latest.get("details").get("previousRole").asString()).isEqualTo("OWNER");
        assertThat(latest.get("details").get("role").asString()).isEqualTo("MEMBER");
    }

    @Test
    void changingOrRemovingAnUnknownMemberIs404() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String stranger = company.path("/members/" + companyWithRoles().userId("MEMBER"));

        assertThat(owner.patch(stranger, Map.of("role", "ADMIN")).status()).isEqualTo(404);
        assertThat(owner.delete(stranger).status()).isEqualTo(404);
    }
}
