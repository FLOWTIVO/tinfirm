package com.flowtivo.tinfirm.audit.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
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
import com.flowtivo.tinfirm.support.TestDatabase;

import tools.jackson.databind.JsonNode;

/**
 * The daily check of the audit chain (the package 6 contract, §3.6, §5.8, §9 {@code AuditCheckTest}): from the head the
 * previous check recorded, it finds a broken event, the newest events removed ({@code SHORTER}, which a full
 * verification does not see) and a head rewritten ({@code HEAD_CHANGED}); a failure is an inbox item of OWNER and ADMIN
 * until an OWNER acknowledges it, and the next check then starts from the head the failed one found.
 */
@ExtendWith(EnterpriseLicence.class)
class AuditCheckTest extends IntegrationTest {

    @Autowired
    private AuditCheckService checks;

    @Test
    void aCheckPassesFromTheChainsStartAndThenFromTheHeadItRecorded() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        long head = auditEvents(owner, company).getFirst().get("seq").asLong();

        AuditCheckStore.CheckRow first = checks.check(company.id()).orElseThrow();

        assertThat(first.ok()).isTrue();
        assertThat(first.fromSeq()).isZero();
        assertThat(first.headSeq()).isGreaterThanOrEqualTo(head);
        assertThat(first.eventsChecked()).isEqualTo(first.headSeq());
        owner.patch(company.path(""), Map.of("name", unique("Renamed")));
        AuditCheckStore.CheckRow second = checks.check(company.id()).orElseThrow();
        assertThat(second.ok()).isTrue();
        assertThat(second.fromSeq()).isEqualTo(first.headSeq());
        assertThat(second.headSeq()).isGreaterThan(first.headSeq());
        assertThat(second.eventsChecked()).isEqualTo(second.headSeq() - first.headSeq());

        JsonNode listed = loginAs(company.email("ADMIN")).get(company.path("/audit-events/checks")).json();
        assertThat(listed.get("items")).hasSize(2);
        assertThat(listed.get("items").get(0).get("id").asString()).isEqualTo(second.id().toString());
        assertThat(listed.get("items").get(0).get("headHash").asString()).hasSize(64);
        assertThat(loginAs(company.email("MEMBER")).get(company.path("/audit-events/checks")).status())
            .isEqualTo(403);
        assertThat(inboxKinds(owner, company)).doesNotContain("AUDIT_CHECK");
    }

    @Test
    void aTruncatedChainFailsShorterUntilAnOwnerAcknowledgesIt() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        AuditCheckStore.CheckRow passed = checks.check(company.id()).orElseThrow();
        long head = passed.headSeq();
        String hashBefore = auditEvents(owner, company).stream()
            .filter(event -> event.get("seq").asLong() == head - 2)
            .findFirst()
            .orElseThrow()
            .get("hash")
            .asString();

        // The newest two events removed and the head moved back: a valid chain, only shorter (ADR-0010).
        tamper(company.id(), "DELETE FROM audit_event WHERE company_id = ? AND seq > " + (head - 2),
                "UPDATE audit_chain_head SET last_seq = " + (head - 2) + ", last_hash = '" + hashBefore
                        + "' WHERE company_id = ?");
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();

        AuditCheckStore.CheckRow failed = checks.check(company.id()).orElseThrow();

        assertThat(failed.ok()).isFalse();
        assertThat(failed.failure()).isEqualTo("SHORTER");
        assertThat(failed.fromSeq()).isEqualTo(head);
        assertThat(failed.headSeq()).isEqualTo(head - 2);
        assertThat(inboxKinds(owner, company)).contains("AUDIT_CHECK");
        ApiClient admin = loginAs(company.email("ADMIN"));
        JsonNode item = item(admin, company, "AUDIT_CHECK");
        assertThat(item.get("auditCheck").get("failure").asString()).isEqualTo("SHORTER");
        assertThat(inboxKinds(loginAs(company.email("MEMBER")), company)).doesNotContain("AUDIT_CHECK");
        // A check again, before anyone acknowledged it, fails the same way.
        AuditCheckStore.CheckRow again = checks.check(company.id()).orElseThrow();
        assertThat(again.failure()).isEqualTo("SHORTER");

        String acknowledge = company.path("/audit-events/checks/" + again.id() + "/acknowledge");
        assertThat(admin.post(acknowledge, Map.of("note", "Restored from a backup")).status()).isEqualTo(403);
        assertThat(owner.post(company.path("/audit-events/checks/" + failed.id() + "/acknowledge"),
                Map.of("note", "an older one")).problemCode()).isEqualTo("VALIDATION_FAILED");
        ApiResponse acknowledged = owner.post(acknowledge, Map.of("note", "Restored from a backup"));
        assertThat(acknowledged.status()).as("%s", acknowledged).isEqualTo(200);
        assertThat(acknowledged.json().get("acknowledgedNote").asString()).isEqualTo("Restored from a backup");
        assertThat(acknowledged.json().get("acknowledgedByUserId").asString())
            .isEqualTo(company.userId("OWNER").toString());
        JsonNode audited = auditEvents(owner, company, "audit.check_acknowledged").getFirst();
        assertThat(audited.get("details").get("checkId").asString()).isEqualTo(again.id().toString());
        assertThat(audited.get("details").get("failure").asString()).isEqualTo("SHORTER");
        assertThat(audited.get("details").toString()).doesNotContain("backup");
        assertThat(inboxKinds(owner, company)).doesNotContain("AUDIT_CHECK");

        // The next check starts from the head the failed one found.
        AuditCheckStore.CheckRow next = checks.check(company.id()).orElseThrow();
        assertThat(next.ok()).isTrue();
        assertThat(next.fromSeq()).isEqualTo(head - 2);
    }

    @Test
    void aRewrittenHeadFailsHeadChanged() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        AuditCheckStore.CheckRow passed = checks.check(company.id()).orElseThrow();
        String forged = "f".repeat(64);

        tamper(company.id(), "UPDATE audit_event SET hash = '" + forged + "' WHERE company_id = ? AND seq = "
                + passed.headSeq(), "UPDATE audit_chain_head SET last_hash = '" + forged + "' WHERE company_id = ?");

        AuditCheckStore.CheckRow failed = checks.check(company.id()).orElseThrow();
        assertThat(failed.ok()).isFalse();
        assertThat(failed.failure()).isEqualTo("HEAD_CHANGED");
        assertThat(failed.firstInvalidSeq()).isNull();
        assertThat(owner.get(company.path("/audit-events/checks")).json().get("items").get(0).get("failure")
            .asString()).isEqualTo("HEAD_CHANGED");
    }

    @Test
    void aChangedEventAfterTheHeadFailsBroken() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        AuditCheckStore.CheckRow passed = checks.check(company.id()).orElseThrow();
        owner.patch(company.path(""), Map.of("name", unique("Once")));
        owner.patch(company.path(""), Map.of("name", unique("Twice")));
        long changed = passed.headSeq() + 1;

        tamper(company.id(), "UPDATE audit_event SET details = '{\"changed\":[\"forged\"]}'::jsonb"
                + " WHERE company_id = ? AND seq = " + changed);

        AuditCheckStore.CheckRow failed = checks.check(company.id()).orElseThrow();
        assertThat(failed.failure()).isEqualTo("BROKEN");
        assertThat(failed.firstInvalidSeq()).isEqualTo(changed);
        // A check that passed is not acknowledged.
        assertThat(owner.post(company.path("/audit-events/checks/" + passed.id() + "/acknowledge"),
                Map.of("note", "nothing to accept")).problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(owner.post(company.path("/audit-events/checks/" + UUID.randomUUID() + "/acknowledge"),
                Map.of("note", "unknown")).status()).isEqualTo(404);
    }

    @Test
    void theInstanceChainIsCheckedAndShownToInstanceAdmins() {
        AuditCheckStore.CheckRow check = checks.check(null).orElseThrow();
        assertThat(check.companyId()).isNull();

        JsonNode listed = admin().get("/api/admin/audit-events/checks").json();
        assertThat(listed.get("items").get(0).get("id").asString()).isEqualTo(check.id().toString());
        TestCompany company = companyWithRoles();
        assertThat(loginAs(company.email("OWNER")).get("/api/admin/audit-events/checks").status()).isEqualTo(403);
    }

    @Test
    void theDailyPassChecksEachChainOnceADay() {
        TestCompany company = companyWithRoles();

        assertThat(checks.checkDue()).isPositive();
        checks.checkDue();

        JsonNode listed = loginAs(company.email("OWNER")).get(company.path("/audit-events/checks")).json();
        assertThat(listed.get("items")).hasSize(1);
        assertThat(listed.get("items").get(0).get("ok").asBoolean()).isTrue();
    }

    private static List<String> inboxKinds(ApiClient reader, TestCompany company) {
        List<String> kinds = new ArrayList<>();
        reader.get(company.path("/inbox")).json().get("items").forEach(item -> kinds.add(item.get("kind").asString()));
        return kinds;
    }

    private static JsonNode item(ApiClient reader, TestCompany company, String kind) {
        for (JsonNode item : reader.get(company.path("/inbox")).json().get("items")) {
            if (item.get("kind").asString().equals(kind)) {
                return item;
            }
        }
        throw new AssertionError("No " + kind + " item");
    }

    /** Changes the chain as its owner, which row-level security and the grants do not stop: the attack checked for. */
    private static void tamper(UUID companyId, String... statements) throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner()) {
            owner.setAutoCommit(false);
            try (Statement ddl = owner.createStatement()) {
                ddl.execute("ALTER TABLE audit_event NO FORCE ROW LEVEL SECURITY");
                ddl.execute("ALTER TABLE audit_chain_head NO FORCE ROW LEVEL SECURITY");
                // The owner's tampering switches off the head's trigger too (V073), as it can.
                ddl.execute("ALTER TABLE audit_chain_head DISABLE TRIGGER audit_chain_head_advance_ck_trg");
                for (String sql : statements) {
                    try (PreparedStatement change = owner.prepareStatement(sql)) {
                        change.setObject(1, companyId);
                        assertThat(change.executeUpdate()).as(sql).isPositive();
                    }
                }
                ddl.execute("ALTER TABLE audit_chain_head ENABLE TRIGGER audit_chain_head_advance_ck_trg");
                ddl.execute("ALTER TABLE audit_event FORCE ROW LEVEL SECURITY");
                ddl.execute("ALTER TABLE audit_chain_head FORCE ROW LEVEL SECURITY");
            }
            owner.commit();
        }
    }
}
