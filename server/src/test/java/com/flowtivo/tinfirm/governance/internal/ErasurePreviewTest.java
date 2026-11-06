package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestContent;
import com.flowtivo.tinfirm.support.TestIdentityContent;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * The erasure's dry run (CONVENTIONS §15.11): with the erasure's request and authorization, it counts what the erasure
 * then removes, says what would remain, and names the holds that would refuse it, changing nothing and recording
 * nothing; every other refusal is the erasure's.
 */
@ExtendWith(EnterpriseLicence.class)
class ErasurePreviewTest extends IntegrationTest {

    @Test
    void anActiveMembersPreviewCountsWhatTheErasureThenRemovesAndChangesNothing() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        TestContent.Content ended = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("ended").toLowerCase(), "TINFIRM_THREAD", true);
        TestContent.Content open = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("open").toLowerCase(), "TINFIRM_THREAD", false);
        long kept = TestContent.secretsIn(company.id(), ended.conversationId());
        int audited = auditEvents(owner, company).size();
        String path = company.path("/principals/" + member + "/erase");

        ApiResponse preview = owner.post(path + "/preview", Map.of());

        assertThat(preview.status()).as("%s", preview).isEqualTo(200);
        JsonNode erases = preview.json().get("erases");
        assertThat(erases.get("conversations").asLong()).isEqualTo(1);
        assertThat(erases.get("messages").asLong()).isEqualTo(2);
        assertThat(erases.get("decisions").asLong()).isEqualTo(2);
        assertThat(erases.get("outputs").asLong()).isEqualTo(1);
        assertThat(erases.get("files").asLong()).isEqualTo(1);
        assertThat(erases.get("runs").asLong()).isEqualTo(1);
        assertThat(erases.get("runEvents").asLong()).isEqualTo(1);
        assertThat(preview.json().get("holds")).isEmpty();
        assertThat(whats(preview.json().get("remains"))).containsExactly("AUDIT", "TOMBSTONES", "RUNNER_SESSIONS",
                "SOURCE_SYSTEM", "OPEN_CONVERSATIONS", "USER_ACCOUNT");
        // Nothing changed and nothing was recorded.
        assertThat(TestContent.secretsIn(company.id(), ended.conversationId())).isEqualTo(kept);
        assertThat(TestContent.column(company.id(), "SELECT erased_at FROM conversation WHERE id = ?",
                ended.conversationId())).isNull();
        assertThat(auditEvents(owner, company)).hasSize(audited);
        assertThat(preview.body()).doesNotContain(TestContent.SECRET).doesNotContain(ended.key());

        // An open conversation named is refused as the erasure refuses it.
        ApiResponse named = owner.post(path + "/preview",
                Map.of("conversationIds", List.of(open.conversationId().toString())));
        assertThat(named.status()).isEqualTo(409);
        assertThat(named.problemCode()).isEqualTo("ERASURE_NOT_ALLOWED");

        ApiResponse erased = owner.post(path, Map.of());

        assertThat(erased.status()).as("%s", erased).isEqualTo(200);
        assertThat(erased.json().get("erased")).isEqualTo(erases);
        assertThat(erased.json().get("remains")).isEqualTo(preview.json().get("remains"));
        // After the erasure, nothing of the ended conversation is left to erase.
        assertThat(owner.post(path + "/preview", Map.of()).json().get("erases").get("messages").asLong()).isZero();
    }

    @Test
    void aLeftMembersPreviewCountsTheirWordsInOthersConversations() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID admin = memberPrincipal(company, company.userId("ADMIN"));
        TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("theirs").toLowerCase(), "TINFIRM_THREAD", false);
        TestContent.Content others = TestContent.conversation(company.id(), agent, admin, company.userId("ADMIN"),
                unique("others").toLowerCase(), "TINFIRM_THREAD", true);
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("""
                    INSERT INTO message (id, company_id, conversation_id, kind, author_principal_id, body, created_at)
                    VALUES (?, ?, ?, 'PERSON', ?, 'Said secret-words there', now())""",
                    UUID.randomUUID(), company.id(), others.conversationId(), member);
            sql.commit();
        }
        assertThat(owner.delete(company.path("/members/" + company.userId("MEMBER"))).status()).isEqualTo(204);
        String path = company.path("/principals/" + member + "/erase");

        JsonNode preview = owner.post(path + "/preview", null).json();

        // Their open conversation (whole, as they left) and the message they said in the admin's.
        assertThat(preview.get("erases").get("conversations").asLong()).isEqualTo(1);
        assertThat(preview.get("erases").get("messages").asLong()).isEqualTo(3);
        assertThat(whats(preview.get("remains"))).contains("AGENT_TEXTS").doesNotContain("OPEN_CONVERSATIONS");
        ApiResponse erased = owner.post(path, Map.of());
        assertThat(erased.status()).as("%s", erased).isEqualTo(200);
        assertThat(erased.json().get("erased")).isEqualTo(preview.get("erases"));
        assertThat(erased.json().get("remains")).isEqualTo(preview.get("remains"));
    }

    @Test
    void thePreviewNamesTheHoldsThatWouldRefuseTheErasureWithTheCountsOnceLifted() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID admin = memberPrincipal(company, company.userId("ADMIN"));
        TestContent.Content ended = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("held").toLowerCase(), "TINFIRM_THREAD", true);
        String path = company.path("/principals/" + member + "/erase");
        String onPerson = HoldTest.hold(owner, company, Map.of("principalId", member.toString(), "reason",
                "Litigation with a supplier"));
        String onConversation = HoldTest.hold(owner, company, Map.of("conversationId",
                ended.conversationId().toString(), "reason", "Audit 2026"));

        JsonNode preview = owner.post(path + "/preview", Map.of()).json();

        assertThat(preview.get("holds")).extracting(hold -> hold.get("id").asString())
            .containsExactlyInAnyOrder(onPerson, onConversation);
        JsonNode personHold = holdOf(preview, onPerson);
        assertThat(personHold.get("reason").asString()).isEqualTo("Litigation with a supplier");
        assertThat(personHold.get("principalId").asString()).isEqualTo(member.toString());
        assertThat(personHold.get("setByUserId").asString()).isEqualTo(company.userId("OWNER").toString());
        assertThat(holdOf(preview, onConversation).get("conversationKey").asString()).isEqualTo(ended.key());
        // The counts are what would go once the holds are lifted; the erasure names the same holds.
        assertThat(preview.get("erases").get("conversations").asLong()).isEqualTo(1);
        ApiResponse refused = owner.post(path, Map.of());
        assertThat(refused.problemCode()).isEqualTo("ON_HOLD");
        assertThat(refused.json().get("holdIds")).extracting(JsonNode::asString)
            .containsExactlyInAnyOrder(onPerson, onConversation);

        owner.delete(company.path("/holds/" + onPerson));
        owner.delete(company.path("/holds/" + onConversation));
        assertThat(owner.post(path + "/preview", Map.of()).json().get("holds")).isEmpty();

        // A trigger's conversation is held through its author: the hold on the admin stops a left member's erasure.
        UUID triggers = TestContent.triggerConversation(company.id(), agent, admin, member,
                unique("trig").toLowerCase());
        assertThat(triggers).isNotNull();
        assertThat(owner.delete(company.path("/members/" + company.userId("MEMBER"))).status()).isEqualTo(204);
        String onAuthor = HoldTest.hold(owner, company, Map.of("principalId", admin.toString(), "reason", "Matter"));
        assertThat(owner.post(path + "/preview", Map.of()).json().get("holds"))
            .extracting(hold -> hold.get("id").asString()).containsExactly(onAuthor);
    }

    @Test
    void onlyTheOwnerPreviewsAndOnlyInTheirCompany() {
        TestCompany company = companyWithRoles();
        TestCompany other = companyWithRoles();
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        String path = company.path("/principals/" + member + "/erase/preview");
        UUID system = createSystem(loginAs(company.email("OWNER")), company, unique("Jira"), "JIRA_DC",
                JiraStub.newInstance().baseUrl(), false);
        String identity = company.path("/systems/" + system + "/identities/jdoe/erase/preview");

        assertThat(loginAs(company.email("ADMIN")).post(path, Map.of()).status()).isEqualTo(403);
        assertThat(loginAs(company.email("MEMBER")).post(path, Map.of()).status()).isEqualTo(403);
        assertThat(loginAs(company.email("ADMIN")).post(identity, null).status()).isEqualTo(403);
        // Another company's OWNER does not see this company at all.
        assertThat(loginAs(other.email("OWNER")).post(path, Map.of()).status()).isEqualTo(404);
        assertThat(loginAs(other.email("OWNER")).post(identity, null).status()).isEqualTo(404);
        // Nor this company's OWNER another company's person or system.
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID theirs = memberPrincipal(other, other.userId("MEMBER"));
        assertThat(owner.post(company.path("/principals/" + theirs + "/erase/preview"), Map.of()).status())
            .isEqualTo(404);
        UUID service = createServiceIdentity(owner, company, unique("Bot"));
        assertThat(owner.post(company.path("/principals/" + service + "/erase/preview"), Map.of()).problemCode())
            .isEqualTo("ERASURE_NOT_ALLOWED");
        assertThat(owner.post(company.path("/systems/" + UUID.randomUUID() + "/identities/jdoe/erase/preview"), null)
            .status()).isEqualTo(404);
    }

    @Test
    void anOutsideIdentitysPreviewCountsTheirWordsAndTheTranscriptsThatQuotedThem() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID system = createSystem(owner, company, unique("Jira"), "JIRA_DC", JiraStub.newInstance().baseUrl(), false);
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        TestContent.Content open = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("chat").toLowerCase(), "CHAT_THREAD", false);
        String externalId = "U" + UUID.randomUUID().toString().substring(0, 8);
        TestIdentityContent.register(company.id(), system, externalId, new TestIdentityContent.Words(
                Set.of(open.conversationId()), Set.of(open.personMessageId()), Set.of(open.runId())));
        String path = company.path("/systems/" + system + "/identities/" + externalId + "/erase");
        String held = HoldTest.hold(owner, company, Map.of("conversationId", open.conversationId().toString(),
                "reason", "Matter"));
        int audited = auditEvents(owner, company).size();

        JsonNode preview = owner.post(path + "/preview", null).json();

        assertThat(preview.get("holds")).extracting(hold -> hold.get("id").asString()).containsExactly(held);
        assertThat(preview.get("erases").get("other").get("messages").asLong()).isEqualTo(1);
        assertThat(preview.get("erases").get("runEvents").asLong()).isEqualTo(1);
        assertThat(whats(preview.get("remains"))).contains("AGENT_TEXTS", "SOURCE_SYSTEM");
        assertThat(TestContent.column(company.id(), "SELECT purged_at FROM message WHERE id = ?",
                open.personMessageId())).isNull();
        assertThat(auditEvents(owner, company)).hasSize(audited);
        owner.delete(company.path("/holds/" + held));

        JsonNode lifted = owner.post(path + "/preview", null).json();
        ApiResponse erased = owner.post(path, null);

        assertThat(lifted.get("holds")).isEmpty();
        assertThat(erased.json().get("erased")).isEqualTo(lifted.get("erases"));
        assertThat(erased.json().get("remains")).isEqualTo(lifted.get("remains"));
        // An identity no module knows: nothing would go.
        JsonNode nobody = owner.post(company.path("/systems/" + system + "/identities/nobody/erase/preview"), null)
            .json();
        assertThat(nobody.get("erases").get("runEvents").asLong()).isZero();
        assertThat(nobody.get("erases").path("other").isNull() || nobody.get("erases").path("other").isMissingNode())
            .isTrue();
    }

    private static JsonNode holdOf(JsonNode preview, String holdId) {
        for (JsonNode hold : preview.get("holds")) {
            if (hold.get("id").asString().equals(holdId)) {
                return hold;
            }
        }
        throw new AssertionError("No hold " + holdId + " in " + preview);
    }

    private static List<String> whats(JsonNode remains) {
        List<String> what = new java.util.ArrayList<>();
        remains.forEach(remain -> what.add(remain.get("what").asString()));
        return what;
    }
}
