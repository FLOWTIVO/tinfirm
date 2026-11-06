package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestContent;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * Legal holds (the package 6 contract, §3.7, §5.9, G16; open point 22, §9 {@code HoldTest}): a held conversation and a
 * held person are skipped by the sweep and purged after the lift; only the OWNER holds; the reason stays on the hold.
 */
@ExtendWith(EnterpriseLicence.class)
class HoldTest extends IntegrationTest {

    @Autowired
    private RetentionSweep sweep;

    @Test
    void aHeldConversationAndAHeldPersonAreSkippedUntilTheLift() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID admin = memberPrincipal(company, company.userId("ADMIN"));
        UUID ownerPrincipal = memberPrincipal(company, company.userId("OWNER"));
        TestContent.Content held = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("held").toLowerCase(), "TINFIRM_THREAD", true);
        TestContent.Content ofHeldPerson = TestContent.conversation(company.id(), agent, admin,
                company.userId("ADMIN"), unique("person").toLowerCase(), "TINFIRM_THREAD", true);
        TestContent.Content free = TestContent.conversation(company.id(), agent, ownerPrincipal,
                company.userId("OWNER"), unique("free").toLowerCase(), "TINFIRM_THREAD", true);
        long everything = TestContent.secretsIn(company.id(), held.conversationId());
        // The conversation to hold, by its key: its id and key, nothing of its content; OWNER only.
        JsonNode found = owner.get(company.path("/holds/conversations/" + held.key())).json();
        assertThat(found.get("conversationId").asString()).isEqualTo(held.conversationId().toString());
        assertThat(found.get("key").asString()).isEqualTo(held.key());
        assertThat(found.size()).isEqualTo(2);
        assertThat(owner.get(company.path("/holds/conversations/no-such-key-1")).status()).isEqualTo(404);
        assertThat(loginAs(company.email("ADMIN")).get(company.path("/holds/conversations/" + held.key())).status())
            .isEqualTo(403);
        String onConversation = hold(owner, company, Map.of("conversationId", held.conversationId().toString(),
                "reason", "Matter 2026-17: the supplier dispute"));
        String onPerson = hold(owner, company, Map.of("principalId", admin.toString(), "reason", "Matter 2026-18"));

        sweep.sweep(company.id(), Instant.now().plus(Duration.ofDays(400)));

        assertThat(TestContent.secretsIn(company.id(), held.conversationId())).isEqualTo(everything);
        assertThat(TestContent.secretsIn(company.id(), ofHeldPerson.conversationId())).isEqualTo(everything);
        assertThat(TestContent.secretsIn(company.id(), free.conversationId())).isZero();
        JsonNode listed = owner.get(company.path("/holds")).json().get("items");
        assertThat(listed).hasSize(2);
        assertThat(listed.get(0).get("principalName").asString()).isNotBlank();
        assertThat(listed.get(1).get("conversationKey").asString()).isEqualTo(held.key());

        assertThat(owner.delete(company.path("/holds/" + onConversation)).status()).isEqualTo(204);
        assertThat(owner.delete(company.path("/holds/" + onPerson)).status()).isEqualTo(204);
        assertThat(owner.delete(company.path("/holds/" + onPerson)).status()).isEqualTo(204);
        // Within 400 days of the lift, so that the lifted holds are still listed.
        sweep.sweep(company.id(), Instant.now().plus(Duration.ofDays(399)));

        assertThat(TestContent.secretsIn(company.id(), held.conversationId())).isZero();
        assertThat(TestContent.secretsIn(company.id(), ofHeldPerson.conversationId())).isZero();
        assertThat(owner.get(company.path("/holds")).json().get("items")).isEmpty();
        assertThat(owner.get(company.path("/holds?lifted=true")).json().get("items")).hasSize(2);

        // Audited with the ids, never the reason; a second lift changes nothing.
        List<JsonNode> set = auditEvents(owner, company, "hold.set");
        assertThat(set).hasSize(2);
        assertThat(set.get(1).get("details").get("conversationId").asString())
            .isEqualTo(held.conversationId().toString());
        assertThat(set.get(0).get("details").get("principalId").asString()).isEqualTo(admin.toString());
        assertThat(auditEvents(owner, company, "hold.lifted")).hasSize(2);
        assertThat(auditEvents(owner, company).toString()).doesNotContain("supplier dispute");
    }

    @Test
    void aTriggersConversationIsHeldThroughItsAuthorAndPurgedUnderAnotherPersonsHold() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID admin = memberPrincipal(company, company.userId("ADMIN"));
        // Trigger's conversations have no person (package 4): each acts for its trigger's author.
        UUID ofMember = TestContent.triggerConversation(company.id(), agent, member, admin,
                unique("trig-held").toLowerCase());
        UUID ofAdmin = TestContent.triggerConversation(company.id(), agent, admin, member,
                unique("trig-free").toLowerCase());
        hold(owner, company, Map.of("principalId", member.toString(), "reason", "Matter"));

        sweep.sweep(company.id(), Instant.now().plus(Duration.ofDays(400)));

        // The member's trigger's conversation is kept whole; in the admin's, only the member's words are kept.
        assertThat(TestContent.secretsIn(company.id(), ofMember)).isEqualTo(2);
        assertThat(TestContent.secretsIn(company.id(), ofAdmin)).isEqualTo(1);
        assertThat(TestContent.column(company.id(), "SELECT content_purged_at FROM conversation WHERE id = ?",
                ofAdmin)).isNotNull();
    }

    @Test
    void onlyTheOwnerHoldsAndAHoldNamesOneMemberOrOneConversation() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        Map<String, Object> onMember = Map.of("principalId", member.toString(), "reason", "Matter");

        assertThat(loginAs(company.email("ADMIN")).post(company.path("/holds"), onMember).status()).isEqualTo(403);
        assertThat(loginAs(company.email("ADMIN")).get(company.path("/holds")).status()).isEqualTo(403);
        assertThat(loginAs(company.email("MEMBER")).get(company.path("/holds")).status()).isEqualTo(403);
        assertThat(owner.post(company.path("/holds"), Map.of("reason", "Nothing named")).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        assertThat(owner.post(company.path("/holds"), Map.of("principalId", member.toString(), "conversationId",
                UUID.randomUUID().toString(), "reason", "Both")).problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(owner.post(company.path("/holds"), Map.of("principalId", member.toString(), "reason", "  "))
            .problemCode()).isEqualTo("VALIDATION_FAILED");
        UUID service = createServiceIdentity(owner, company, unique("Bot"));
        assertThat(owner.post(company.path("/holds"), Map.of("principalId", service.toString(), "reason", "A bot"))
            .problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(owner.post(company.path("/holds"), Map.of("conversationId", UUID.randomUUID().toString(),
                "reason", "Unknown")).status()).isEqualTo(404);
        assertThat(owner.delete(company.path("/holds/" + UUID.randomUUID())).status()).isEqualTo(404);
        assertThat(owner.post(company.path("/holds"), onMember).status()).isEqualTo(201);
    }

    @Test
    void aLiftedHoldIsDeleted400DaysAfterItsLift() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String id = hold(owner, company, Map.of("principalId",
                memberPrincipal(company, company.userId("MEMBER")).toString(), "reason", "Matter"));
        owner.delete(company.path("/holds/" + id));

        sweep.sweep(company.id(), Instant.now().plus(Duration.ofDays(399)));
        assertThat(owner.get(company.path("/holds?lifted=true")).json().get("items")).hasSize(1);
        RetentionSweep.Swept swept = sweep.sweep(company.id(), Instant.now().plus(Duration.ofDays(401)));

        assertThat(swept.records()).containsEntry("liftedHolds", 1L);
        assertThat(owner.get(company.path("/holds?lifted=true")).json().get("items")).isEmpty();
    }

    static String hold(ApiClient owner, TestCompany company, Map<String, Object> body) {
        ApiResponse response = owner.post(company.path("/holds"), body);
        assertThat(response.status()).as("hold: %s", response).isEqualTo(201);
        return response.json().get("id").asString();
    }
}
