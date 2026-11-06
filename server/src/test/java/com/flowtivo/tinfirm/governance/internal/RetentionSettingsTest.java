package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestContent;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * The retention periods (the package 6 contract, §3.7, §5.9; open point 16): the defaults, admins reading, the OWNER
 * changing them within their ranges, audited, and the answer saying what the next sweep purges.
 */
@ExtendWith(EnterpriseLicence.class)
class RetentionSettingsTest extends IntegrationTest {

    @Test
    void theDefaultsAreReadByAdminsAndChangedByTheOwnerOnly() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));

        JsonNode defaults = admin.get(company.path("/retention")).json();
        assertThat(defaults.get("defaults").asBoolean()).isTrue();
        assertThat(defaults.get("runEventsDays").asInt()).isEqualTo(90);
        assertThat(defaults.get("chatContentDays").asInt()).isEqualTo(30);
        assertThat(defaults.get("contentDays").asInt()).isEqualTo(365);
        assertThat(defaults.get("filesDays").asInt()).isEqualTo(365);
        assertThat(loginAs(company.email("MEMBER")).get(company.path("/retention")).status()).isEqualTo(403);
        Map<String, Object> periods = periods(30, 7, 60, null);
        assertThat(admin.put(company.path("/retention"), periods).status()).isEqualTo(403);

        ApiResponse changed = owner.put(company.path("/retention"), periods);

        assertThat(changed.status()).as("%s", changed).isEqualTo(200);
        assertThat(changed.json().get("defaults").asBoolean()).isFalse();
        assertThat(changed.json().get("contentDays").asInt()).isEqualTo(60);
        assertThat(changed.json().has("filesDays") && !changed.json().get("filesDays").isNull()).isFalse();
        assertThat(changed.json().get("updatedByUserId").asString()).isEqualTo(company.userId("OWNER").toString());
        JsonNode audited = auditEvents(owner, company, "retention.changed").getFirst();
        assertThat(audited.get("details").get("runEventsDays").asInt()).isEqualTo(30);
        assertThat(audited.get("details").has("filesDays") && !audited.get("details").get("filesDays").isNull())
            .isFalse();
        assertThat(audited.get("details").get("changed")).extracting(JsonNode::asString)
            .containsExactly("runEventsDays", "chatContentDays", "contentDays", "filesDays");
        // The same again changes nothing and is not audited.
        assertThat(owner.put(company.path("/retention"), periods).status()).isEqualTo(200);
        assertThat(auditEvents(owner, company, "retention.changed")).hasSize(1);
    }

    @Test
    void periodsOutOfRangeAreRefused() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));

        for (Map<String, Object> invalid : new Map[] { periods(6, 30, 365, 365), periods(3651, 30, 365, 365),
                periods(90, 0, 365, 365), periods(90, 30, 29, 365), periods(90, 30, 365, 6),
                periods(90, 400, 365, 365) }) {
            ApiResponse refused = owner.put(company.path("/retention"), invalid);
            assertThat(refused.status()).as("%s", invalid).isEqualTo(400);
            assertThat(refused.problemCode()).as("%s", invalid).isEqualTo("RETENTION_INVALID");
        }
        // Chats' content may be kept for long while other content is kept for good.
        assertThat(owner.put(company.path("/retention"), periods(90, 400, null, null)).status()).isEqualTo(200);
    }

    @Test
    void theAnswerSaysWhatTheNextSweepPurges() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        TestContent.Content ended = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("due").toLowerCase(), "TINFIRM_THREAD", true);
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("UPDATE conversation SET ended_at = ended_at - interval '100 days' WHERE id = ?",
                    ended.conversationId());
            sql.accepted("UPDATE run SET finished_at = finished_at - interval '100 days' WHERE id = ?",
                    ended.runId());
            sql.commit();
        }

        JsonNode before = owner.get(company.path("/retention")).json().get("due");
        assertThat(before.get("conversations").asLong()).isZero();
        assertThat(before.get("files").asLong()).isZero();
        assertThat(before.get("runs").asLong()).isEqualTo(1);

        JsonNode after = owner.put(company.path("/retention"), periods(90, 30, 60, 60)).json().get("due");
        assertThat(after.get("conversations").asLong()).isEqualTo(1);
        assertThat(after.get("files").asLong()).isEqualTo(1);
    }

    @Test
    void aConversationDueUnderBothPeriodsCountsOnceAsThePreviewCountsIt() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID user = company.userId("MEMBER");
        // A chat's conversation ended 100 days ago: due under the chats' period and under the content's.
        TestContent.Content chat = TestContent.conversation(company.id(), agent, member, user,
                unique("chat").toLowerCase(), "CHAT_THREAD", true);
        // A held conversation and an open one, whose runs ended as long ago: the sweep purges neither's events.
        TestContent.Content held = TestContent.conversation(company.id(), agent, member, user,
                unique("held").toLowerCase(), "TINFIRM_THREAD", true);
        TestContent.Content open = TestContent.conversation(company.id(), agent, member, user,
                unique("open").toLowerCase(), "TINFIRM_THREAD", false);
        try (TenantSql sql = TenantSql.in(company.id())) {
            for (TestContent.Content content : new TestContent.Content[] { chat, held, open }) {
                sql.accepted("UPDATE conversation SET ended_at = ended_at - interval '100 days' WHERE id = ?",
                        content.conversationId());
                sql.accepted("UPDATE run SET finished_at = finished_at - interval '100 days' WHERE id = ?",
                        content.runId());
            }
            sql.commit();
        }
        HoldTest.hold(owner, company, Map.of("conversationId", held.conversationId().toString(), "reason", "A"));
        Map<String, Object> periods = periods(90, 30, 60, 60);

        JsonNode saved = owner.put(company.path("/retention"), periods).json();

        JsonNode due = saved.get("due");
        assertThat(due.get("conversations").asLong()).as("once, not per period").isEqualTo(1);
        assertThat(due.get("runs").asLong()).as("the ended, unheld conversation's run only").isEqualTo(1);
        assertThat(due.get("files").asLong()).isEqualTo(1);
        JsonNode next = saved.get("nextSweep");
        assertThat(next.get("conversations").asLong()).isEqualTo(1);
        assertThat(next.get("messages").asLong()).isEqualTo(2);
        assertThat(next.get("runEvents").asLong()).isEqualTo(1);
        // The page's count and the preview's are one: due from nextSweep, and nextSweep the preview's current.
        JsonNode read = owner.get(company.path("/retention")).json();
        assertThat(read.get("nextSweep")).isEqualTo(next);
        assertThat(read.get("due")).isEqualTo(due);
        JsonNode preview = owner.post(company.path("/retention/preview"), periods).json();
        assertThat(preview.get("current")).isEqualTo(next);
        assertThat(due.get("conversations")).isEqualTo(next.get("conversations"));
        assertThat(due.get("runs")).isEqualTo(next.get("runEvents"));
        assertThat(due.get("files")).isEqualTo(next.get("files"));
    }

    private static Map<String, Object> periods(int runEvents, int chat, Integer content, Integer files) {
        Map<String, Object> body = new HashMap<>();
        body.put("runEventsDays", runEvents);
        body.put("chatContentDays", chat);
        body.put("contentDays", content);
        body.put("filesDays", files);
        return body;
    }
}
