package com.flowtivo.tinfirm.principal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * Principals over HTTP (the package 1 contract, §3.1, §3.2, §6 and §8): a member's principal comes and goes with the
 * membership, a rejoining member gets a new one; admins manage service identities and the groups that may use them,
 * every member lists them with whether they may use each; suspending and resuming is a kill switch; every change is
 * audited and announced.
 */
class PrincipalIntegrationTest extends IntegrationTest {

    @Test
    void aMembersPrincipalLivesWithTheMembershipAndARejoiningMemberGetsANewOne() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String email = uniqueEmail("leaver");
        UUID userId = createUser(admin(), email, false);
        addMember(owner, company.id(), email, "MEMBER");
        UUID first = memberPrincipal(company, userId);

        assertThat(owner.delete(company.path("/members/" + userId)).status()).isEqualTo(204);
        assertThat(status(company, first)).isEqualTo("LEFT");
        addMember(owner, company.id(), email, "MEMBER");
        UUID second = memberPrincipal(company, userId);
        assertThat(second).isNotEqualTo(first);
        assertThat(status(company, first)).isEqualTo("LEFT");
        assertThat(status(company, second)).isEqualTo("ACTIVE");

        // Each member of the company has one principal; the bootstrap admin, who left, has a LEFT one.
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM principal WHERE kind = 'MEMBER' AND status <> 'LEFT'"))
                .isEqualTo(4);
            assertThat(sql.count("SELECT count(*) FROM principal WHERE kind = 'MEMBER' AND status = 'LEFT'"))
                .isEqualTo(2);
        }

        // Audited with the user's id, never their email; announced after the commit.
        List<JsonNode> created = auditEvents(owner, company, "principal.created");
        assertThat(created).anySatisfy(event -> {
            assertThat(event.get("targetId").asString()).isEqualTo(second.toString());
            assertThat(event.get("details").get("kind").asString()).isEqualTo("MEMBER");
            assertThat(event.get("details").get("userId").asString()).isEqualTo(userId.toString());
        });
        List<JsonNode> left = auditEvents(owner, company, "principal.left");
        assertThat(left).anySatisfy(event -> {
            assertThat(event.get("targetId").asString()).isEqualTo(first.toString());
            assertThat(event.get("details").get("userId").asString()).isEqualTo(userId.toString());
        });
        auditEvents(owner, company).forEach(event -> assertThat(event.get("details").toString()).doesNotContain("@"));
        assertThat(RecordedEvents.of(company.id(), PrincipalEvents.PrincipalLeft.class))
            .contains(new PrincipalEvents.PrincipalLeft(company.id(), first, userId));
    }

    @Test
    void adminsManageServiceIdentitiesAndEveryMemberListsThem() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));

        ApiResponse created = admin.post(company.path("/service-identities"),
                Map.of("name", " Jira bot ", "description", "Polls the support project"));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        JsonNode bot = created.json();
        assertThat(bot.get("name").asString()).isEqualTo("Jira bot");
        assertThat(bot.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(bot.get("usableByMe").asBoolean()).as("ADMIN needs a group").isFalse();
        assertThat(bot.get("userGroups")).isEmpty();
        String path = company.path("/service-identities/" + bot.get("id").asString());

        // Unique ignoring case; a blank or too long name is refused; members do not manage.
        ApiResponse duplicate = owner.post(company.path("/service-identities"), Map.of("name", "JIRA BOT"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.problemCode()).isEqualTo("DUPLICATE_SERVICE_IDENTITY_NAME");
        assertThat(owner.post(company.path("/service-identities"), Map.of("name", "  ")).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        assertThat(owner.post(company.path("/service-identities"), Map.of("name", "x".repeat(101))).status())
            .isEqualTo(400);
        assertThat(member.post(company.path("/service-identities"), Map.of("name", "Mine")).status()).isEqualTo(403);
        assertThat(member.patch(path, Map.of("name", "Mine")).status()).isEqualTo(403);
        assertThat(member.post(path + "/suspend", Map.of()).status()).isEqualTo(403);
        assertThat(member.put(path + "/users", Map.of("groupIds", List.of())).status()).isEqualTo(403);
        assertThat(member.delete(path).status()).isEqualTo(403);

        // Every member lists them, without the groups; OWNER may use every one.
        JsonNode listed = member.get(company.path("/service-identities")).json().get("items");
        assertThat(listed).singleElement().satisfies(item -> {
            assertThat(item.get("name").asString()).isEqualTo("Jira bot");
            assertThat(item.get("description").asString()).isEqualTo("Polls the support project");
            assertThat(item.get("usableByMe").asBoolean()).isFalse();
            assertThat(item.has("userGroups")).isFalse();
        });
        assertThat(owner.get(path).json().get("usableByMe").asBoolean()).isTrue();

        // A rename and a cleared description; a request that changes nothing is not audited.
        ApiResponse renamed = admin.patch(path, Map.of("name", "Support bot", "description", " "));
        assertThat(renamed.status()).as("%s", renamed).isEqualTo(200);
        assertThat(renamed.json().get("name").asString()).isEqualTo("Support bot");
        assertThat(renamed.json().has("description")).isFalse();
        assertThat(admin.patch(path, Map.of("name", "Support bot")).status()).isEqualTo(200);
        List<JsonNode> changed = auditEvents(owner, company, "principal.changed");
        assertThat(changed).singleElement().satisfies(event -> {
            assertThat(event.get("details").get("changed")).extracting(JsonNode::asString)
                .containsExactly("name", "description");
            assertThat(event.get("details").get("name").asString()).isEqualTo("Support bot");
        });

        // Deleted, with an audit by name; then it is gone for everyone.
        assertThat(admin.delete(path).status()).isEqualTo(204);
        assertThat(member.get(path).status()).isEqualTo(404);
        assertThat(admin.delete(path).status()).isEqualTo(404);
        assertThat(auditEvents(owner, company, "principal.deleted")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("name").asString()).isEqualTo("Support bot"));
    }

    @Test
    void groupsGiveTheRightToUseAServiceIdentity() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID memberId = company.userId("MEMBER");
        UUID analysts = group(owner, company, "Analysts", List.of(memberId));
        UUID empty = group(owner, company, "Nobody", List.of());
        String botId = admin.post(company.path("/service-identities"), Map.of("name", "Bot")).json().get("id")
            .asString();
        String path = company.path("/service-identities/" + botId);

        // A group of another company, or none at all, is refused.
        TestCompany other = companyWithRoles();
        UUID foreign = group(loginAs(other.email("OWNER")), other, "Foreign", List.of());
        ApiResponse refused = admin.put(path + "/users", Map.of("groupIds", List.of(foreign.toString())));
        assertThat(refused.status()).isEqualTo(400);
        assertThat(refused.problemCode()).isEqualTo("VALIDATION_FAILED");

        ApiResponse replaced = admin.put(path + "/users",
                Map.of("groupIds", List.of(analysts.toString(), empty.toString(), analysts.toString())));
        assertThat(replaced.status()).as("%s", replaced).isEqualTo(200);
        assertThat(replaced.json().get("userGroups")).extracting(item -> item.get("name").asString())
            .containsExactly("Analysts", "Nobody");
        assertThat(member.get(path).json().get("usableByMe").asBoolean()).isTrue();
        assertThat(admin.get(path).json().get("usableByMe").asBoolean()).as("ADMIN is in no group").isFalse();
        assertThat(admin.put(path + "/users", Map.of("groupIds", List.of(empty.toString(), analysts.toString())))
            .status()).isEqualTo(200);

        JsonNode audit = auditEvents(owner, company, "service_identity.users_changed").getFirst();
        assertThat(audit.get("targetId").asString()).isEqualTo(botId);
        assertThat(audit.get("details").get("added")).hasSize(2);
        assertThat(auditEvents(owner, company, "service_identity.users_changed")).as("an unchanged replace")
            .hasSize(1);

        // Deleting a group takes its right away, audited and announced with the cause.
        assertThat(owner.delete(company.path("/groups/" + analysts)).status()).isEqualTo(204);
        assertThat(member.get(path).json().get("usableByMe").asBoolean()).isFalse();
        JsonNode byDeletion = auditEvents(owner, company, "service_identity.users_changed").getFirst();
        assertThat(byDeletion.get("details").get("cause").asString()).isEqualTo("group.deleted");
        assertThat(byDeletion.get("details").get("removed")).extracting(JsonNode::asString)
            .containsExactly(analysts.toString());
        assertThat(RecordedEvents.of(company.id(), PrincipalEvents.ServiceIdentityUsersChanged.class))
            .extracting(PrincipalEvents.ServiceIdentityUsersChanged::removed)
            .containsExactly(java.util.Set.of(), java.util.Set.of(analysts));
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM service_identity_user WHERE group_id = ?", analysts)).isZero();
        }
    }

    @Test
    void suspendingAndResumingIsAuditedOnlyWhenItChangesSomething() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        UUID botId = UUID.fromString(admin.post(company.path("/service-identities"), Map.of("name", "Bot")).json()
            .get("id").asString());
        String path = company.path("/service-identities/" + botId);

        JsonNode suspended = admin.post(path + "/suspend", Map.of("reason", "Its token leaked")).json();
        assertThat(suspended.get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(suspended.get("suspendedAt").asString()).isNotBlank();
        assertThat(suspended.get("suspensionReason").asString()).isEqualTo("Its token leaked");
        assertThat(admin.post(path + "/suspend", Map.of()).json().get("status").asString()).isEqualTo("SUSPENDED");
        JsonNode listed = owner.get(company.path("/service-identities")).json().get("items").get(0);
        assertThat(listed.get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(listed.get("suspendedAt").asString()).isEqualTo(suspended.get("suspendedAt").asString());
        assertThat(listed.get("suspensionReason").asString()).isEqualTo("Its token leaked");
        // A member sees when, never the reason typed.
        JsonNode seenByMember = loginAs(company.email("MEMBER")).get(path).json();
        assertThat(seenByMember.get("suspendedAt").asString()).isEqualTo(suspended.get("suspendedAt").asString());
        assertThat(seenByMember.has("suspensionReason")).isFalse();
        JsonNode resumed = admin.post(path + "/resume", Map.of()).json();
        assertThat(resumed.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(resumed.has("suspendedAt")).isFalse();
        assertThat(resumed.has("suspensionReason")).isFalse();
        assertThat(admin.post(path + "/resume", Map.of()).json().get("status").asString()).isEqualTo("ACTIVE");

        assertThat(auditEvents(owner, company, "principal.suspended")).hasSize(1);
        assertThat(auditEvents(owner, company, "principal.resumed")).hasSize(1);
        assertThat(RecordedEvents.of(company.id(), PrincipalEvents.PrincipalSuspended.class))
            .containsExactly(new PrincipalEvents.PrincipalSuspended(company.id(), botId));
        assertThat(RecordedEvents.of(company.id(), PrincipalEvents.PrincipalResumed.class))
            .containsExactly(new PrincipalEvents.PrincipalResumed(company.id(), botId));

        // A member's principal is not a service identity.
        UUID memberPrincipal = memberPrincipal(company, company.userId("MEMBER"));
        assertThat(admin.post(company.path("/service-identities/" + memberPrincipal + "/suspend"), Map.of()).status())
            .isEqualTo(404);
        assertThat(admin.get(company.path("/service-identities/" + memberPrincipal)).status()).isEqualTo(404);
    }

    private UUID group(ApiClient owner, TestCompany company, String name, List<UUID> members) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("memberIds", members.stream().map(UUID::toString).toList());
        ApiResponse response = owner.post(company.path("/groups"), body);
        assertThat(response.status()).as("group: %s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    private static String status(TestCompany company, UUID principalId) throws SQLException {
        try (TenantSql sql = TenantSql.in(company.id())) {
            return (String) sql.column("SELECT status FROM principal WHERE id = ?", principalId).getFirst();
        }
    }
}
