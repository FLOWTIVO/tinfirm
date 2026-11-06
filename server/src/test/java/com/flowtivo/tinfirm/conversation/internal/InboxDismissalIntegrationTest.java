package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.support.TestTriggers;

import tools.jackson.databind.JsonNode;

/**
 * Dismissing a notice of the inbox (CONVENTIONS §15.7): each member for themselves, while the notice's key stands; the
 * notice back once its condition, and so its key, changes, and at the undo; never one the reader is the one to act on
 * ({@code 409 NOTICE_NOT_DISMISSIBLE}); {@code counts.notices} without the dismissed ones; nothing audited; the
 * dismissals gone with the member, their erasure, at a later dismissal once their notice left, and after 400 days.
 */
class InboxDismissalIntegrationTest extends IntegrationTest {

    @Autowired
    private InboxDismissalRetention retention;

    @Autowired
    private SystemTransactions transactions;

    @Test
    void eachMemberDismissesANoticeForThemselvesAndUndoesIt() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        companySpendCrossed(company);
        String key = "COMPANY_SPEND:" + YearMonth.now(ZoneOffset.UTC);
        // Every notice names its key and whether its reader may dismiss it; the other kinds neither.
        JsonNode notice = notice(admin, company, "COMPANY_SPEND");
        assertThat(notice.get("noticeKey").asString()).isEqualTo(key);
        assertThat(notice.get("dismissible").asBoolean()).isTrue();
        assertThat(inbox(admin, company).get("counts").get("notices").asInt()).isEqualTo(1);
        int audited = auditEvents(owner, company).size();

        // As the web's client sends it: the key's ':' encoded.
        ApiResponse dismissed = admin.put(dismissal(company, key.replace(":", "%3A")), null);
        assertThat(dismissed.status()).as("%s", dismissed).isEqualTo(204);
        assertThat(kinds(admin, company)).doesNotContain("COMPANY_SPEND");
        assertThat(inbox(admin, company).get("counts").get("notices").asInt()).isZero();
        // For the admin alone: the owner still sees it.
        assertThat(kinds(owner, company)).contains("COMPANY_SPEND");
        assertThat(inbox(owner, company).get("counts").get("notices").asInt()).isEqualTo(1);
        // Again: nothing changes.
        assertThat(admin.put(dismissal(company, key), null).status()).isEqualTo(204);
        assertThat(dismissals(company)).isEqualTo(1);

        // The undo shows it again; an undo of nothing changes nothing.
        assertThat(admin.delete(dismissal(company, key)).status()).isEqualTo(204);
        assertThat(kinds(admin, company)).contains("COMPANY_SPEND");
        assertThat(admin.delete(dismissal(company, key)).status()).isEqualTo(204);
        assertThat(dismissals(company)).isZero();

        // Neither is audited: it is the reader's view of their own inbox (§8).
        assertThat(auditEvents(owner, company)).hasSize(audited);

        // A notice that is not the caller's: 404, as for a key that names nothing; a key out of its pattern: 400.
        assertThat(member.put(dismissal(company, key), null).status()).isEqualTo(404);
        assertThat(admin.put(dismissal(company, "COMPANY_SPEND:1999-01"), null).status()).isEqualTo(404);
        assertThat(admin.put(dismissal(company, "no~such~key"), null).problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(admin.delete(dismissal(company, "no~such~key")).problemCode()).isEqualTo("VALIDATION_FAILED");
        // An instance admin who is not a member reads the admins' notices under break-glass, and dismisses none.
        JsonNode breakGlass = notice(admin(), company, "COMPANY_SPEND");
        assertThat(breakGlass.get("noticeKey").asString()).isEqualTo(key);
        assertThat(breakGlass.get("dismissible").asBoolean()).isFalse();
        assertThat(admin().put(dismissal(company, key), null).status()).isEqualTo(404);
        assertThat(admin().delete(dismissal(company, key)).status()).isEqualTo(404);
        // Another company's member: 404 (the tenant isolation test calls both paths too).
        TestCompany other = companyWithRoles();
        assertThat(loginAs(other.email("OWNER")).put(dismissal(company, key), null).status()).isEqualTo(404);
    }

    @Test
    void aDismissedNoticeComesBackWhenItsConditionChanges() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID memberP = memberPrincipal(company, company.userId("MEMBER"));
        UUID trigger = TestTriggers.schedule(company.id(), TestRuns.agent(company.id()), memberP, memberP);
        ApiResponse paused = member.post(company.path("/triggers/" + trigger + "/pause"), Map.of());
        assertThat(paused.status()).as("%s", paused).isEqualTo(200);
        JsonNode notice = notice(member, company, "TRIGGER");
        assertThat(notice.get("trigger").get("reason").asString()).isEqualTo("PAUSED");
        assertThat(notice.get("dismissible").asBoolean()).isTrue();
        String key = notice.get("noticeKey").asString();
        assertThat(key).startsWith("TRIGGER:" + trigger + ":PAUSED:USER:");

        assertThat(member.put(dismissal(company, key), null).status()).isEqualTo(204);
        assertThat(kinds(member, company)).doesNotContain("TRIGGER");

        // Resumed and paused again: another pause, another key, so the notice is back.
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("""
                    UPDATE trigger SET status = 'ACTIVE', paused_reason = NULL, paused_at = NULL, paused_by = NULL
                     WHERE id = ?""", trigger);
            sql.accepted("""
                    UPDATE trigger SET status = 'PAUSED', paused_reason = 'USER', paused_at = now() + interval '1 second'
                     WHERE id = ?""", trigger);
            sql.commit();
        }
        JsonNode again = notice(member, company, "TRIGGER");
        assertThat(again.get("noticeKey").asString()).startsWith("TRIGGER:" + trigger + ":PAUSED:USER:")
            .isNotEqualTo(key);
        assertThat(inbox(member, company).get("counts").get("notices").asInt()).isEqualTo(1);
        // The former pause's dismissal matches nothing now; it stays for the daily sweep, beside the new one.
        assertThat(dismissals(company)).isEqualTo(1);
        assertThat(member.put(dismissal(company, again.get("noticeKey").asString()), null).status()).isEqualTo(204);
        assertThat(dismissals(company)).isEqualTo(2);
        assertThat(kinds(member, company)).doesNotContain("TRIGGER");
    }

    @Test
    void whatAsksTheReaderToActIsNotDismissed() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        // The member leaves: the admins' departure waits for its checklist.
        ApiResponse removed = owner.delete(company.path("/members/" + company.userId("MEMBER")));
        assertThat(removed.status()).as("%s", removed).isEqualTo(204);
        JsonNode departure = notice(owner, company, "DEPARTURE");
        assertThat(departure.get("dismissible").asBoolean()).isFalse();
        String key = departure.get("noticeKey").asString();
        assertThat(key).startsWith("DEPARTURE:" + departure.get("departure").get("principalId").asString() + ":");

        ApiResponse refused = owner.put(dismissal(company, key), null);
        assertThat(refused.status()).as("%s", refused).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("NOTICE_NOT_DISMISSIBLE");
        assertThat(kinds(owner, company)).contains("DEPARTURE");
        assertThat(dismissals(company)).isZero();
    }

    @Test
    void dismissalsGoWithTheMemberWithTheirErasureAndAfter400Days() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        companySpendCrossed(company);
        String key = "COMPANY_SPEND:" + YearMonth.now(ZoneOffset.UTC);
        UUID adminP = memberPrincipal(company, company.userId("ADMIN"));

        // The erasure of the person: their dismissals go, counted.
        assertThat(admin.put(dismissal(company, key), null).status()).isEqualTo(204);
        ApiResponse erased = owner.post(company.path("/principals/" + adminP + "/erase"), null);
        assertThat(erased.status()).as("%s", erased).isEqualTo(200);
        assertThat(erased.json().get("erased").get("other").get("inboxDismissals").asLong()).isEqualTo(1);
        assertThat(dismissals(company)).isZero();
        assertThat(kinds(admin, company)).contains("COMPANY_SPEND");

        // The daily sweep: kept for 400 days.
        assertThat(owner.put(dismissal(company, key), null).status()).isEqualTo(204);
        assertThat(sweep(company, Instant.now().plus(Duration.ofDays(399)))).isZero();
        assertThat(sweep(company, Instant.now().plus(Duration.ofDays(401)))).isEqualTo(1);
        assertThat(kinds(owner, company)).contains("COMPANY_SPEND");

        // The member who leaves takes theirs along, in the leaving transaction.
        assertThat(admin.put(dismissal(company, key), null).status()).isEqualTo(204);
        assertThat(owner.put(dismissal(company, key), null).status()).isEqualTo(204);
        assertThat(dismissals(company)).isEqualTo(2);
        assertThat(owner.delete(company.path("/members/" + company.userId("ADMIN"))).status()).isEqualTo(204);
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM inbox_dismissal WHERE principal_id = ?", adminP)).isZero();
        }
        assertThat(dismissals(company)).isEqualTo(1);
    }

    @Test
    void theTableKeepsItsRulesAsTheRuntimeRole() throws SQLException {
        TestCompany company = companyWithRoles();
        TestCompany other = companyWithRoles();
        UUID memberP = memberPrincipal(company, company.userId("MEMBER"));
        UUID bot = createServiceIdentity(loginAs(company.email("OWNER")), company, unique("bot"));
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("""
                    INSERT INTO inbox_dismissal (company_id, principal_id, notice_key, dismissed_at)
                    VALUES (?, ?, 'COMPANY_SPEND:2026-10', now())""", company.id(), memberP);
            // One row per member and key.
            assertThat(sql.refused("""
                    INSERT INTO inbox_dismissal (company_id, principal_id, notice_key, dismissed_at)
                    VALUES (?, ?, 'COMPANY_SPEND:2026-10', now())""", company.id(), memberP))
                .isEqualTo("inbox_dismissal_pk");
            // A member's principal only, and a key of its pattern.
            assertThat(sql.refused("""
                    INSERT INTO inbox_dismissal (company_id, principal_id, notice_key, dismissed_at)
                    VALUES (?, ?, 'COMPANY_SPEND:2026-10', now())""", company.id(), bot))
                .isEqualTo("inbox_dismissal_principal_fk");
            assertThat(sql.refused("""
                    INSERT INTO inbox_dismissal (company_id, principal_id, notice_key, dismissed_at)
                    VALUES (?, ?, 'a key', now())""", company.id(), memberP)).isEqualTo("inbox_dismissal_key_ck");
            // Written once and removed, never changed.
            assertThat(sql.refused("UPDATE inbox_dismissal SET dismissed_at = now() WHERE principal_id = ?", memberP))
                .contains("permission denied");
            sql.commit();
            // Another company's context sees none of it, and writes none for it.
            sql.context(other.id());
            assertThat(sql.count("SELECT count(*) FROM inbox_dismissal WHERE principal_id = ?", memberP)).isZero();
            assertThat(sql.refused("""
                    INSERT INTO inbox_dismissal (company_id, principal_id, notice_key, dismissed_at)
                    VALUES (?, ?, 'COMPANY_SPEND:2026-11', now())""", company.id(), memberP))
                .contains("row-level security");
        }
    }

    // ---- helpers -----------------------------------------------------------------------------------------------------

    /** The company's spend crossed its monthly alert amount this month (UTC): the admins' COMPANY_SPEND notice. */
    private static void companySpendCrossed(TestCompany company) {
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("""
                    INSERT INTO company_spend_alert (company_id, month, crossed_at, total_usd, alert_usd)
                    VALUES (?, ?, ?, 1250, 1000)""", company.id(),
                    YearMonth.now(ZoneOffset.UTC).atDay(1),
                    Instant.now().truncatedTo(ChronoUnit.MICROS).atOffset(ZoneOffset.UTC));
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private long sweep(TestCompany company, Instant now) {
        return transactions.inCompany(company.id(), () -> retention.sweep(company.id(), now));
    }

    private static String dismissal(TestCompany company, String key) {
        return company.path("/inbox/dismissals/" + key);
    }

    private static long dismissals(TestCompany company) {
        try (TenantSql sql = TenantSql.in(company.id())) {
            return sql.count("SELECT count(*) FROM inbox_dismissal");
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JsonNode inbox(ApiClient client, TestCompany company) {
        ApiResponse response = client.get(company.path("/inbox"));
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.json();
    }

    private static List<String> kinds(ApiClient client, TestCompany company) {
        List<String> kinds = new ArrayList<>();
        inbox(client, company).get("items").forEach(item -> kinds.add(item.get("kind").asString()));
        return kinds;
    }

    private static JsonNode notice(ApiClient client, TestCompany company, String kind) {
        List<JsonNode> found = new ArrayList<>();
        inbox(client, company).get("items").forEach(item -> {
            if (item.get("kind").asString().equals(kind)) {
                found.add(item);
            }
        });
        assertThat(found).as("%s items", kind).hasSize(1);
        return found.getFirst();
    }
}
