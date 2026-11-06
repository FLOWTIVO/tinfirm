package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
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
 * Erasing content on request (the package 6 contract, §5.9, G11, G16; open point 17, §9 {@code ErasureTest}): a
 * person's ended conversations, a left member's words everywhere, an outside identity's words in an open conversation
 * with the run events that quoted them; refused under a hold; the answer naming what remains.
 */
@ExtendWith(EnterpriseLicence.class)
class ErasureTest extends IntegrationTest {

    @Test
    void anActiveMembersEndedConversationsAreErasedAndAnOpenOneNamedIsRefused() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        TestContent.Content ended = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("ended").toLowerCase(), "TINFIRM_THREAD", true);
        TestContent.Content open = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("open").toLowerCase(), "TINFIRM_THREAD", false);
        long everything = TestContent.secretsIn(company.id(), open.conversationId());
        String erase = company.path("/principals/" + member + "/erase");

        ApiResponse refused = owner.post(erase, Map.of("conversationIds", List.of(open.conversationId().toString())));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("ERASURE_NOT_ALLOWED");
        assertThat(loginAs(company.email("ADMIN")).post(erase, Map.of()).status()).isEqualTo(403);

        ApiResponse erased = owner.post(erase, Map.of());

        assertThat(erased.status()).as("%s", erased).isEqualTo(200);
        JsonNode counts = erased.json().get("erased");
        assertThat(counts.get("conversations").asLong()).isEqualTo(1);
        assertThat(counts.get("messages").asLong()).isEqualTo(2);
        assertThat(counts.get("decisions").asLong()).isEqualTo(2);
        assertThat(counts.get("outputs").asLong()).isEqualTo(1);
        assertThat(counts.get("files").asLong()).isEqualTo(1);
        assertThat(counts.get("runs").asLong()).isEqualTo(1);
        assertThat(counts.get("runEvents").asLong()).isEqualTo(1);
        assertThat(remains(erased)).contains("AUDIT", "TOMBSTONES", "RUNNER_SESSIONS", "SOURCE_SYSTEM",
                "OPEN_CONVERSATIONS", "USER_ACCOUNT");
        assertThat(TestContent.secretsIn(company.id(), ended.conversationId())).isZero();
        assertThat(TestContent.secretsIn(company.id(), open.conversationId())).isEqualTo(everything);
        JsonNode view = owner.get(company.path("/conversations/" + ended.conversationId())).json();
        assertThat(view.get("erasedAt").isNull()).isFalse();
        assertThat(view.get("title").asString()).isEqualTo(ended.key());

        JsonNode audited = auditEvents(owner, company, "principal.erased").getFirst();
        assertThat(audited.get("details").get("principalId").asString()).isEqualTo(member.toString());
        assertThat(audited.get("details").get("erased").get("conversations").asLong()).isEqualTo(1);
        assertThat(audited.get("details").toString()).doesNotContain(TestContent.SECRET)
            .doesNotContain(company.email("MEMBER"));
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
    }

    @Test
    void aMemberWhoLeftIsErasedInTheirConversationsAndInOthers() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID admin = memberPrincipal(company, company.userId("ADMIN"));
        TestContent.Content theirs = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("theirs").toLowerCase(), "TINFIRM_THREAD", false);
        TestContent.Content others = TestContent.conversation(company.id(), agent, admin, company.userId("ADMIN"),
                unique("others").toLowerCase(), "TINFIRM_THREAD", true);
        UUID said = UUID.randomUUID();
        try (TenantSql sql = TenantSql.in(company.id())) {
            // What they said in a conversation that is not theirs (as in a trigger's conversation of package 4).
            sql.accepted("""
                    INSERT INTO message (id, company_id, conversation_id, kind, author_principal_id, body, created_at)
                    VALUES (?, ?, ?, 'PERSON', ?, 'Said secret-words there', now())""",
                    said, company.id(), others.conversationId(), member);
            sql.commit();
        }
        assertThat(owner.delete(company.path("/members/" + company.userId("MEMBER"))).status()).isEqualTo(204);

        ApiResponse erased = owner.post(company.path("/principals/" + member + "/erase"), Map.of());

        assertThat(erased.status()).as("%s", erased).isEqualTo(200);
        assertThat(TestContent.secretsIn(company.id(), theirs.conversationId())).isZero();
        assertThat(TestContent.column(company.id(), "SELECT purged_at FROM message WHERE id = ?", said)).isNotNull();
        // The other person's own words stay.
        assertThat(TestContent.column(company.id(), "SELECT body FROM message WHERE id = ?",
                others.personMessageId())).isNotNull();
        assertThat(remains(erased)).contains("AGENT_TEXTS").doesNotContain("OPEN_CONVERSATIONS");
        assertThat(auditEvents(owner, company, "principal.erased").getFirst().get("details").get("left").asBoolean())
            .isTrue();
    }

    @Test
    void aMemberWhoLeftIsForgottenAsWhoTestedASystem() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        UUID tester = memberPrincipal(company, company.userId("ADMIN"));
        UUID jira = createSystem(owner, company, "Jira", "JIRA_DC", JiraStub.newInstance().baseUrl(), false);
        String system = company.path("/systems/" + jira);
        assertThat(admin.post(system + "/test", Map.of()).status()).isEqualTo(200);
        JsonNode tested = owner.get(system).json().get("lastTest");
        assertThat(tested.get("byPrincipalId").asString()).isEqualTo(tester.toString());

        // An active member's erasure takes their ended conversations only: the test still names them.
        ApiResponse active = owner.post(company.path("/principals/" + tester + "/erase"), Map.of());
        assertThat(active.status()).as("%s", active).isEqualTo(200);
        assertThat(active.json().get("erased").has("other")).isFalse();
        assertThat(owner.get(system).json().get("lastTest").get("byName").asString())
            .isEqualTo("User " + company.email("ADMIN"));

        // Once they left, their erasure forgets them as the tester: the system's page names nobody; when and how the
        // test ended stay.
        assertThat(owner.delete(company.path("/members/" + company.userId("ADMIN"))).status()).isEqualTo(204);
        ApiResponse erased = owner.post(company.path("/principals/" + tester + "/erase"), Map.of());
        assertThat(erased.status()).as("%s", erased).isEqualTo(200);
        assertThat(erased.json().get("erased").get("other").get("systemTests").asLong()).isEqualTo(1);
        JsonNode forgotten = owner.get(system).json().get("lastTest");
        assertThat(forgotten.has("byPrincipalId")).isFalse();
        assertThat(forgotten.has("byName")).isFalse();
        assertThat(forgotten.get("ok").asBoolean()).isTrue();
        assertThat(forgotten.get("at").asString()).isEqualTo(tested.get("at").asString());
        assertThat(owner.get(system).body()).doesNotContain("User " + company.email("ADMIN"));
        assertThat(auditEvents(owner, company, "principal.erased").getFirst().get("details").get("erased")
            .get("other").get("systemTests").asLong()).isEqualTo(1);
    }

    @Test
    void aMemberWhoLeftIsErasedInATriggersConversationWhichItsAuthorsHoldKeeps() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID admin = memberPrincipal(company, company.userId("ADMIN"));
        // The member spoke in the conversation of the admin's trigger, which has no person (package 4).
        UUID triggers = TestContent.triggerConversation(company.id(), agent, admin, member,
                unique("trig-erase").toLowerCase());
        assertThat(owner.delete(company.path("/members/" + company.userId("MEMBER"))).status()).isEqualTo(204);
        String erase = company.path("/principals/" + member + "/erase");
        String onAuthor = HoldTest.hold(owner, company, Map.of("principalId", admin.toString(), "reason", "Matter"));

        ApiResponse refused = owner.post(erase, Map.of());

        assertThat(refused.problemCode()).as("%s", refused).isEqualTo("ON_HOLD");
        assertThat(refused.json().get("holdIds")).extracting(JsonNode::asString).containsExactly(onAuthor);
        assertThat(TestContent.secretsIn(company.id(), triggers)).isEqualTo(2);
        owner.delete(company.path("/holds/" + onAuthor));

        ApiResponse erased = owner.post(erase, Map.of());

        assertThat(erased.status()).as("%s", erased).isEqualTo(200);
        assertThat(erased.json().get("erased").get("messages").asLong()).isEqualTo(1);
        // Their words went; the conversation, nobody's personal one, keeps the agent's.
        assertThat(TestContent.secretsIn(company.id(), triggers)).isEqualTo(1);
        assertThat(TestContent.column(company.id(), "SELECT erased_at FROM conversation WHERE id = ?", triggers))
            .isNull();
    }

    @Test
    void anOpenHoldRefusesTheErasureUntilItIsLifted() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        TestContent.Content ended = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("hold").toLowerCase(), "TINFIRM_THREAD", true);
        String erase = company.path("/principals/" + member + "/erase");
        String onPerson = HoldTest.hold(owner, company, Map.of("principalId", member.toString(), "reason", "Matter"));
        String onConversation = HoldTest.hold(owner, company, Map.of("conversationId",
                ended.conversationId().toString(), "reason", "Matter"));

        ApiResponse refused = owner.post(erase, Map.of());

        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("ON_HOLD");
        assertThat(refused.json().get("holdIds")).extracting(JsonNode::asString)
            .containsExactlyInAnyOrder(onPerson, onConversation);
        assertThat(TestContent.secretsIn(company.id(), ended.conversationId())).isPositive();
        owner.delete(company.path("/holds/" + onPerson));
        ApiResponse stillHeld = owner.post(erase, Map.of());
        assertThat(stillHeld.problemCode()).isEqualTo("ON_HOLD");
        assertThat(stillHeld.json().get("holdIds")).extracting(JsonNode::asString).containsExactly(onConversation);
        owner.delete(company.path("/holds/" + onConversation));
        assertThat(owner.post(erase, Map.of()).status()).isEqualTo(200);
        assertThat(TestContent.secretsIn(company.id(), ended.conversationId())).isZero();
    }

    @Test
    void aServiceIdentityAndAnUnknownPrincipalHaveNothingToErase() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID service = createServiceIdentity(owner, company, unique("Bot"));

        assertThat(owner.post(company.path("/principals/" + service + "/erase"), Map.of()).problemCode())
            .isEqualTo("ERASURE_NOT_ALLOWED");
        assertThat(owner.post(company.path("/principals/" + UUID.randomUUID() + "/erase"), Map.of()).status())
            .isEqualTo(404);
    }

    @Test
    void anOutsideIdentitysWordsAreErasedInAnOpenConversationWithTheRunEventsThatQuotedThem() {
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
        String erase = company.path("/systems/" + system + "/identities/" + externalId + "/erase");
        String held = HoldTest.hold(owner, company, Map.of("conversationId", open.conversationId().toString(),
                "reason", "Matter"));
        assertThat(owner.post(erase, null).problemCode()).isEqualTo("ON_HOLD");
        owner.delete(company.path("/holds/" + held));

        ApiResponse erased = owner.post(erase, null);

        assertThat(erased.status()).as("%s", erased).isEqualTo(200);
        assertThat(erased.json().get("erased").get("other").get("messages").asLong()).isEqualTo(1);
        assertThat(erased.json().get("erased").get("runEvents").asLong()).isEqualTo(1);
        assertThat(remains(erased)).contains("AGENT_TEXTS", "SOURCE_SYSTEM");
        // The open conversation shows the marker in the words' place; the agent's message stays.
        JsonNode messages = loginAs(company.email("MEMBER"))
            .get(company.path("/conversations/" + open.conversationId())).json().get("messages");
        assertThat(messages.get(0).get("body").asString()).isEqualTo("[This message was removed.]");
        assertThat(messages.get(1).get("body").asString()).contains(TestContent.SECRET);
        assertThat(TestContent.column(company.id(), "SELECT count(*) FROM run_event WHERE run_id = ?", open.runId()))
            .isEqualTo(0L);
        JsonNode audited = auditEvents(owner, company, "identity.erased").getFirst();
        assertThat(audited.get("details").get("systemId").asString()).isEqualTo(system.toString());
        assertThat(audited.get("details").get("externalId").asString()).isEqualTo(externalId);

        // An identity no module knows: nothing to erase; an unknown system: 404.
        ApiResponse nothing = owner.post(company.path("/systems/" + system + "/identities/nobody/erase"), null);
        assertThat(nothing.json().get("erased").get("runEvents").asLong()).isZero();
        assertThat(owner.post(company.path("/systems/" + UUID.randomUUID() + "/identities/nobody/erase"), null)
            .status()).isEqualTo(404);
    }

    private static List<String> remains(ApiResponse erased) {
        List<String> what = new ArrayList<>();
        erased.json().get("remains").forEach(remain -> what.add(remain.get("what").asString()));
        return what;
    }
}
