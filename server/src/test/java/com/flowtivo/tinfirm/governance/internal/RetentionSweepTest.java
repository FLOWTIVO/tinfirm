package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
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
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestContent;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * The daily retention sweep (the package 6 contract, §5.9, §9 {@code RetentionSweepTest}): each kind on time, open
 * conversations untouched and reported, the tombstones and the audit chain kept and verifying (G11, G12).
 */
@ExtendWith(EnterpriseLicence.class)
class RetentionSweepTest extends IntegrationTest {

    @Autowired
    private RetentionSweep sweep;

    @Test
    void eachKindGoesOnTimeAndOpenConversationsStay() throws Exception {
        TestCompany company = companyWithRoles();
        UUID agent = TestRuns.agent(company.id());
        UUID person = memberPrincipal(company, company.userId("MEMBER"));
        UUID user = company.userId("MEMBER");
        TestContent.Content ended = TestContent.conversation(company.id(), agent, person, user,
                unique("ended").toLowerCase(), "TINFIRM_THREAD", true);
        TestContent.Content chat = TestContent.conversation(company.id(), agent, person, user,
                unique("chat").toLowerCase(), "CHAT_THREAD", true);
        TestContent.Content open = TestContent.conversation(company.id(), agent, person, user,
                unique("open").toLowerCase(), "TINFIRM_THREAD", false);
        long everything = TestContent.secretsIn(company.id(), ended.conversationId());
        Instant now = Instant.now();

        // After 31 days: a chat's messages and decisions' texts.
        sweep.sweep(company.id(), now.plus(Duration.ofDays(31)));
        assertThat(count(company.id(), "SELECT count(*) FROM message WHERE conversation_id = ? AND purged_at IS NULL",
                chat.conversationId())).isZero();
        assertThat(count(company.id(), "SELECT count(*) FROM decision WHERE conversation_id = ? AND purged_at IS NULL",
                chat.conversationId())).isZero();
        assertThat(TestContent.secretsIn(company.id(), ended.conversationId())).isEqualTo(everything);

        // After 91 days: the events of the ended conversations' runs.
        sweep.sweep(company.id(), now.plus(Duration.ofDays(91)));
        assertThat(count(company.id(), "SELECT count(*) FROM run_event WHERE run_id = ?", ended.runId())).isZero();
        assertThat(count(company.id(), "SELECT count(*) FROM run_event WHERE run_id = ?", open.runId())).isEqualTo(2);
        assertThat(TestContent.secretsIn(company.id(), ended.conversationId())).isEqualTo(everything - 2);

        // After 366 days: the rest of the ended conversations' content and files.
        sweep.sweep(company.id(), now.plus(Duration.ofDays(366)));
        assertThat(TestContent.secretsIn(company.id(), ended.conversationId())).isZero();
        assertThat(TestContent.secretsIn(company.id(), chat.conversationId())).isZero();
        assertThat(TestContent.secretsIn(company.id(), open.conversationId())).isEqualTo(everything);

        // The tombstone stays.
        try (TenantSql sql = TenantSql.in(company.id())) {
            Map<String, Object> row = sql.rows("""
                    SELECT c.key, c.turn_count, c.state, c.content_purged_at, a.file_name, a.size_bytes, a.sha256,
                           d.outcome, o.url, r.status, r.texts_purged_at, r.events_purged_at
                      FROM conversation c JOIN attachment a ON a.conversation_id = c.id
                      JOIN decision d ON d.conversation_id = c.id AND d.kind = 'APPROVAL'
                      JOIN output o ON o.conversation_id = c.id JOIN run r ON r.conversation_id = c.id
                     WHERE c.id = ?""", ended.conversationId()).getFirst();
            assertThat(row.get("key")).isEqualTo(ended.key());
            assertThat(row.get("turn_count")).isEqualTo(1);
            assertThat(row.get("content_purged_at")).isNotNull();
            assertThat(row.get("file_name")).isEqualTo("notes.txt");
            assertThat((Long) row.get("size_bytes")).isPositive();
            assertThat(row.get("sha256")).isNotNull();
            assertThat(row.get("outcome")).isEqualTo("APPROVED");
            assertThat(row.get("url")).isEqualTo("https://example.test/report");
            assertThat(row.get("status")).isEqualTo("SUCCEEDED");
            assertThat(row.get("texts_purged_at")).isNotNull();
            assertThat(row.get("events_purged_at")).isNotNull();
        }

        // The API shows what was purged.
        ApiClient member = loginAs(company.email("MEMBER"));
        JsonNode view = member.get(company.path("/conversations/" + ended.conversationId())).json();
        assertThat(view.get("title").asString()).isEqualTo(ended.key());
        assertThat(view.get("contentPurgedAt").isNull()).isFalse();
        assertThat(view.get("messages")).allSatisfy(message -> {
            assertThat(message.get("body").asString()).isEqualTo("[This message was removed.]");
            assertThat(message.get("purgedAt").isNull()).isFalse();
        });
        assertThat(view.get("decisions")).allSatisfy(decision -> {
            assertThat(decision.get("purgedAt").isNull()).isFalse();
            assertThat(decision.has("question") && !decision.get("question").isNull()).isFalse();
        });
        assertThat(view.get("outputs").get(0).get("title").asString()).isEmpty();
        assertThat(view.get("attachments").get(0).get("contentPurgedAt").isNull()).isFalse();
        assertThat(view.get("attachments").get(0).get("sizeBytes").asLong()).isPositive();
        assertThat(view.toString()).doesNotContain(TestContent.SECRET);
        ApiResponse download = member.get(company.path("/conversations/" + ended.conversationId() + "/attachments/"
                + ended.attachmentId()));
        assertThat(download.status()).isEqualTo(410);
        assertThat(download.problemCode()).isEqualTo("CONTENT_PURGED");
        JsonNode run = member.get(company.path("/runs/" + ended.runId())).json();
        assertThat(run.get("eventsPurgedAt").isNull()).isFalse();
        assertThat(run.get("textsPurgedAt").isNull()).isFalse();
        assertThat(run.has("summary") && !run.get("summary").isNull()).isFalse();
        assertThat(member.get(company.path("/runs/" + ended.runId() + "/events")).json().get("items")).isEmpty();
        assertThat(member.get(company.path("/conversations/" + open.conversationId())).body())
            .contains(TestContent.SECRET);

        // Audited once per sweep that purged, as SYSTEM, with counts only; the chain is untouched and verifies.
        ApiClient owner = loginAs(company.email("OWNER"));
        List<JsonNode> purged = auditEvents(owner, company, "content.purged");
        assertThat(purged).hasSize(3);
        JsonNode last = purged.getFirst();
        assertThat(last.get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(last.get("details").get("content").get("conversations").asLong()).isEqualTo(2);
        assertThat(last.get("details").get("files").asLong()).isEqualTo(2);
        assertThat(last.get("details").toString()).doesNotContain(TestContent.SECRET);
        sweep.sweep(company.id(), now.plus(Duration.ofDays(366)));
        assertThat(auditEvents(owner, company, "content.purged")).hasSize(3);
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
        assertThat(owner.get(company.path("/retention")).json().get("lastSweptAt").isNull()).isFalse();
    }

    @Test
    void openConversationsOlderThanTheirPeriodAreReportedNeverPurged() throws Exception {
        TestCompany company = companyWithRoles();
        UUID agent = TestRuns.agent(company.id());
        UUID person = memberPrincipal(company, company.userId("MEMBER"));
        TestContent.Content open = TestContent.conversation(company.id(), agent, person, company.userId("MEMBER"),
                unique("old").toLowerCase(), "TINFIRM_THREAD", false);
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("UPDATE conversation SET created_at = created_at - interval '400 days' WHERE id = ?",
                    open.conversationId());
            sql.commit();
        }

        RetentionSweep.Swept swept = sweep.sweep(company.id(), Instant.now());

        assertThat(swept.openOlder()).isEqualTo(1);
        assertThat(TestContent.secretsIn(company.id(), open.conversationId())).isPositive();
        ApiClient owner = loginAs(company.email("OWNER"));
        JsonNode item = null;
        for (JsonNode candidate : owner.get(company.path("/inbox")).json().get("items")) {
            if (candidate.get("kind").asString().equals("RETENTION_OPEN")) {
                item = candidate;
            }
        }
        assertThat(item).isNotNull();
        assertThat(item.get("openConversations").asInt()).isEqualTo(1);
        assertThat(kinds(loginAs(company.email("ADMIN")), company)).doesNotContain("RETENTION_OPEN");
        assertThat(kinds(loginAs(company.email("MEMBER")), company)).doesNotContain("RETENTION_OPEN");
        JsonNode listed = owner.get(company.path("/retention/open-conversations")).json().get("items");
        assertThat(listed).singleElement().satisfies(conversation -> {
            assertThat(conversation.get("key").asString()).isEqualTo(open.key());
            assertThat(conversation.get("place").asString()).isEqualTo("TINFIRM_THREAD");
            assertThat(conversation.get("ageDays").asInt()).isGreaterThanOrEqualTo(399);
            assertThat(conversation.has("title")).isFalse();
        });
        assertThat(owner.get(company.path("/retention")).json().get("openOlder").asInt()).isEqualTo(1);
        assertThat(loginAs(company.email("ADMIN")).get(company.path("/retention/open-conversations")).status())
            .isEqualTo(403);
    }

    @Test
    void keptContentAndFilesStayWhateverTheTime() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        Map<String, Object> keep = new HashMap<>();
        keep.put("runEventsDays", 7);
        keep.put("chatContentDays", 1);
        keep.put("contentDays", null);
        keep.put("filesDays", null);
        assertThat(owner.put(company.path("/retention"), keep).status()).isEqualTo(200);
        UUID agent = TestRuns.agent(company.id());
        UUID person = memberPrincipal(company, company.userId("MEMBER"));
        TestContent.Content ended = TestContent.conversation(company.id(), agent, person, company.userId("MEMBER"),
                unique("kept").toLowerCase(), "TINFIRM_THREAD", true);
        long everything = TestContent.secretsIn(company.id(), ended.conversationId());

        sweep.sweep(company.id(), Instant.now().plus(Duration.ofDays(3650)));

        // Only the run's two events went.
        assertThat(TestContent.secretsIn(company.id(), ended.conversationId())).isEqualTo(everything - 2);
    }

    private static long count(UUID companyId, String sql, Object... params) throws Exception {
        try (TenantSql tenant = TenantSql.in(companyId)) {
            return tenant.count(sql, params);
        }
    }

    private static List<String> kinds(ApiClient reader, TestCompany company) {
        List<String> kinds = new ArrayList<>();
        reader.get(company.path("/inbox")).json().get("items").forEach(item -> kinds.add(item.get("kind").asString()));
        return kinds;
    }
}
