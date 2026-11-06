package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
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
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

import tools.jackson.databind.JsonNode;

/**
 * The preview of proposed retention periods (CONVENTIONS §15.11): what the next sweep would purge under them and under
 * the stored ones, and what the open holds keep, counted by the sweep's own selection; the Owner's alone, checked as a
 * change is, storing and recording nothing. The forecast equals what the sweep then purges, kind by kind.
 */
@ExtendWith(EnterpriseLicence.class)
class RetentionPreviewTest extends IntegrationTest {

    @Autowired
    private RetentionService retention;

    @Autowired
    private RetentionStore store;

    @Autowired
    private RetentionSweep sweep;

    @Autowired
    private SystemTransactions transactions;

    @Test
    void thePreviewCountsWhatTheProposedPeriodsWouldPurgeAndStoresNothing() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        TestContent.Content ended = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("due").toLowerCase(), "TINFIRM_THREAD", true);
        endedDaysAgo(company.id(), ended, 100);
        int audited = auditEvents(owner, company).size();

        ApiResponse preview = owner.post(company.path("/retention/preview"), periods(90, 30, 60, 60));

        assertThat(preview.status()).as("%s", preview).isEqualTo(200);
        assertCounts(preview.json().get("proposed"), 1, 2, 2, 1, 1, 1, 1);
        // The defaults keep content and files for a year: only the run's events, ended 100 days ago, are due.
        assertCounts(preview.json().get("current"), 0, 0, 0, 0, 0, 0, 1);
        assertCounts(preview.json().get("held"), 0, 0, 0, 0, 0, 0, 0);
        // Nothing stored, nothing purged, nothing recorded.
        JsonNode stored = owner.get(company.path("/retention")).json();
        assertThat(stored.get("defaults").asBoolean()).isTrue();
        assertThat(stored.get("contentDays").asInt()).isEqualTo(365);
        assertThat(TestContent.secretsIn(company.id(), ended.conversationId())).isPositive();
        assertThat(auditEvents(owner, company)).hasSize(audited);

        // The stored periods themselves: both sides alike.
        JsonNode same = owner.post(company.path("/retention/preview"), periods(90, 30, 365, 365)).json();
        assertThat(same.get("proposed")).isEqualTo(same.get("current"));
        // Kept for good: nothing of the content or the files.
        assertCounts(owner.post(company.path("/retention/preview"), periods(90, 30, null, null)).json()
            .get("proposed"), 0, 0, 0, 0, 0, 0, 1);
    }

    @Test
    void theProposedPeriodsAreCheckedAsAChangeAndOnlyTheOwnerPreviewsThem() {
        TestCompany company = companyWithRoles();
        TestCompany other = companyWithRoles();
        String path = company.path("/retention/preview");

        ApiResponse invalid = loginAs(company.email("OWNER")).post(path, periods(90, 30, 29, 60));
        assertThat(invalid.status()).isEqualTo(400);
        assertThat(invalid.problemCode()).isEqualTo("RETENTION_INVALID");
        ApiResponse chatsLonger = loginAs(company.email("OWNER")).post(path, periods(90, 400, 365, 60));
        assertThat(chatsLonger.problemCode()).isEqualTo("RETENTION_INVALID");
        assertThat(loginAs(company.email("ADMIN")).post(path, periods(90, 30, 60, 60)).status()).isEqualTo(403);
        assertThat(loginAs(company.email("MEMBER")).post(path, periods(90, 30, 60, 60)).status()).isEqualTo(403);
        assertThat(loginAs(other.email("OWNER")).post(path, periods(90, 30, 60, 60)).status()).isEqualTo(404);
    }

    @Test
    void thePreviewSaysWhatTheOpenHoldsKeep() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID admin = memberPrincipal(company, company.userId("ADMIN"));
        TestContent.Content theirs = TestContent.conversation(company.id(), agent, member, company.userId("MEMBER"),
                unique("theirs").toLowerCase(), "TINFIRM_THREAD", true);
        TestContent.Content others = TestContent.conversation(company.id(), agent, admin, company.userId("ADMIN"),
                unique("others").toLowerCase(), "TINFIRM_THREAD", true);
        try (TenantSql sql = TenantSql.in(company.id())) {
            // The member said something in the admin's conversation too.
            sql.accepted("""
                    INSERT INTO message (id, company_id, conversation_id, kind, author_principal_id, body, created_at)
                    VALUES (?, ?, ?, 'PERSON', ?, 'Said secret-words there', now())""",
                    UUID.randomUUID(), company.id(), others.conversationId(), member);
            sql.commit();
        }
        endedDaysAgo(company.id(), theirs, 100);
        endedDaysAgo(company.id(), others, 100);
        String path = company.path("/retention/preview");
        String onConversation = HoldTest.hold(owner, company, Map.of("conversationId",
                theirs.conversationId().toString(), "reason", "Audit 2026"));

        JsonNode held = owner.post(path, periods(90, 30, 60, 60)).json();

        // The admin's conversation goes, all of it; the held one stays whole.
        assertCounts(held.get("proposed"), 1, 3, 2, 1, 1, 1, 1);
        assertCounts(held.get("held"), 1, 2, 2, 1, 1, 1, 1);
        assertCounts(held.get("current"), 0, 0, 0, 0, 0, 0, 1);

        owner.delete(company.path("/holds/" + onConversation));
        HoldTest.hold(owner, company, Map.of("principalId", member.toString(), "reason", "Litigation"));

        JsonNode person = owner.post(path, periods(90, 30, 60, 60)).json();

        // A hold on the person keeps their conversation and their words in the admin's, which goes but for them.
        assertCounts(person.get("proposed"), 1, 2, 2, 1, 1, 1, 1);
        assertCounts(person.get("held"), 1, 3, 2, 1, 1, 1, 1);
    }

    @Test
    void theForecastIsWhatTheSweepThenPurgesKindByKind() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID agent = TestRuns.agent(company.id());
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID admin = memberPrincipal(company, company.userId("ADMIN"));
        UUID user = company.userId("MEMBER");
        TestContent.Content ended = TestContent.conversation(company.id(), agent, member, user,
                unique("ended").toLowerCase(), "TINFIRM_THREAD", true);
        TestContent.conversation(company.id(), agent, member, user, unique("chat").toLowerCase(), "CHAT_THREAD", true);
        TestContent.conversation(company.id(), agent, member, user, unique("open").toLowerCase(), "TINFIRM_THREAD",
                false);
        TestContent.Content held = TestContent.conversation(company.id(), agent, member, user,
                unique("held").toLowerCase(), "TINFIRM_THREAD", true);
        TestContent.conversation(company.id(), agent, admin, company.userId("ADMIN"), unique("admins").toLowerCase(),
                "TINFIRM_THREAD", true);
        try (TenantSql sql = TenantSql.in(company.id())) {
            // The held admin's words in the member's ended conversation stay when it goes.
            sql.accepted("""
                    INSERT INTO message (id, company_id, conversation_id, kind, author_principal_id, body, created_at)
                    VALUES (?, ?, ?, 'PERSON', ?, 'Said secret-words here', now())""",
                    UUID.randomUUID(), company.id(), ended.conversationId(), admin);
            sql.commit();
        }
        HoldTest.hold(owner, company, Map.of("conversationId", held.conversationId().toString(), "reason", "A"));
        HoldTest.hold(owner, company, Map.of("principalId", admin.toString(), "reason", "B"));
        Instant now = Instant.now();

        // After 31 days the chat's words, after 91 the ended conversations' transcripts, after 366 the rest.
        RetentionService.Forecast chat = forecastThenSweep(company.id(), now.plus(Duration.ofDays(31)));
        assertThat(chat.purged().messages()).isEqualTo(2);
        assertThat(chat.purged().decisions()).isEqualTo(2);
        RetentionService.Forecast events = forecastThenSweep(company.id(), now.plus(Duration.ofDays(91)));
        assertThat(events.runEvents()).isEqualTo(2);
        RetentionService.Forecast rest = forecastThenSweep(company.id(), now.plus(Duration.ofDays(366)));
        assertThat(rest.purged().conversations()).isEqualTo(2);
        assertThat(rest.purged().messages()).isEqualTo(2);
        assertThat(rest.purged().outputs()).isEqualTo(2);
        assertThat(rest.purged().files()).isEqualTo(2);
        assertThat(rest.runTexts()).isEqualTo(2);
        RetentionService.Forecast none = forecastThenSweep(company.id(), now.plus(Duration.ofDays(367)));
        assertThat(none).isEqualTo(RetentionService.Forecast.NONE);
    }

    /** The forecast of the stored periods as of {@code at}, which must equal what the sweep as of then purges. */
    private RetentionService.Forecast forecastThenSweep(UUID companyId, Instant at) {
        RetentionService.Forecast forecast = transactions.inCompany(companyId,
                () -> retention.forecast(companyId, store.periods(companyId), at, store.held()));
        RetentionSweep.Swept swept = sweep.sweep(companyId, at);
        assertThat(forecast.purged().conversations()).as("conversations").isEqualTo(swept.content().conversations()
                + swept.chat().conversations());
        assertThat(forecast.purged().messages()).as("messages").isEqualTo(swept.chat().messages()
                + swept.content().messages());
        assertThat(forecast.purged().decisions()).as("decisions").isEqualTo(swept.chat().decisions()
                + swept.content().decisions());
        assertThat(forecast.purged().outputs()).as("outputs").isEqualTo(swept.content().outputs());
        assertThat(forecast.purged().files()).as("files").isEqualTo(swept.files().files());
        assertThat(forecast.runTexts()).as("runs' texts").isEqualTo(swept.runTexts());
        assertThat(forecast.runEvents()).as("runs' events").isEqualTo(swept.runEvents());
        return forecast;
    }

    private static void endedDaysAgo(UUID companyId, TestContent.Content content, int days) throws Exception {
        try (TenantSql sql = TenantSql.in(companyId)) {
            sql.accepted("UPDATE conversation SET ended_at = ended_at - make_interval(days => ?) WHERE id = ?", days,
                    content.conversationId());
            sql.accepted("UPDATE run SET finished_at = finished_at - make_interval(days => ?) WHERE id = ?", days,
                    content.runId());
            sql.commit();
        }
    }

    private static void assertCounts(JsonNode counts, long conversations, long messages, long decisions,
            long outputs, long files, long runs, long runEvents) {
        assertThat(counts.get("conversations").asLong()).as("conversations in %s", counts).isEqualTo(conversations);
        assertThat(counts.get("messages").asLong()).as("messages in %s", counts).isEqualTo(messages);
        assertThat(counts.get("decisions").asLong()).as("decisions in %s", counts).isEqualTo(decisions);
        assertThat(counts.get("outputs").asLong()).as("outputs in %s", counts).isEqualTo(outputs);
        assertThat(counts.get("files").asLong()).as("files in %s", counts).isEqualTo(files);
        assertThat(counts.get("runs").asLong()).as("runs in %s", counts).isEqualTo(runs);
        assertThat(counts.get("runEvents").asLong()).as("runEvents in %s", counts).isEqualTo(runEvents);
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
