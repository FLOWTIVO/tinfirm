package com.flowtivo.tinfirm.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;

import tools.jackson.databind.JsonNode;

@ExtendWith(EnterpriseLicence.class)
class AuditChainIntegrationTest extends IntegrationTest {

    @Test
    void anUntouchedChainVerifies() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        owner.patch(company.path(""), Map.of("name", "Verified Co"));

        ApiResponse verification = owner.get(company.path("/audit-events/verification"));

        assertThat(verification.status()).isEqualTo(200);
        assertThat(verification.json().get("valid").asBoolean()).isTrue();
        // company.created, member.added for the creator and the three test members, the creator's leaving, the
        // rename, and what other modules record about the same (a member's principal): every event is checked.
        List<String> actions = actionsOldestFirst(owner, company);
        assertThat(actions.getFirst()).isEqualTo("company.created");
        assertThat(actions.stream().filter("member.added"::equals)).hasSize(4);
        assertThat(actions).contains("member.removed").endsWith("company.updated");
        assertThat(verification.json().get("checkedEvents").asLong()).isEqualTo(actions.size());
        assertThat(verification.json().has("firstInvalidSeq")).isFalse();
    }

    @Test
    void eventsAreListedNewestFirstAndChained() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));

        List<JsonNode> events = new ArrayList<>();
        String cursor = null;
        do {
            JsonNode page = admin.get(company.path("/audit-events?limit=3" + (cursor != null ? "&cursor=" + cursor : "")))
                .json();
            page.get("items").forEach(events::add);
            cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
        }
        while (cursor != null);

        assertThat(events).hasSize(actionsOldestFirst(admin, company).size()).hasSizeGreaterThanOrEqualTo(6);
        for (int i = 0; i < events.size() - 1; i++) {
            JsonNode newer = events.get(i);
            JsonNode older = events.get(i + 1);
            assertThat(newer.get("seq").asLong()).isEqualTo(older.get("seq").asLong() + 1);
            assertThat(newer.get("prevHash").asString()).isEqualTo(older.get("hash").asString());
            assertThat(newer.get("companyId").asString()).isEqualTo(company.id().toString());
        }
        JsonNode first = events.getLast();
        assertThat(first.get("action").asString()).isEqualTo("company.created");
        assertThat(first.get("prevHash").asString()).isEqualTo("0".repeat(64));
        // Personal data is not part of audit details.
        events.forEach(event -> assertThat(event.get("details").toString()).doesNotContain("@"));
    }

    @Test
    void membersCannotReadTheAuditLog() {
        TestCompany company = companyWithRoles();
        for (String role : new String[] { "MEMBER" }) {
            ApiClient member = loginAs(company.email(role));
            assertThat(member.get(company.path("/audit-events")).status()).as(role).isEqualTo(403);
            assertThat(member.get(company.path("/audit-events/verification")).status()).as(role).isEqualTo(403);
        }
    }

    @Test
    void aModifiedEventIsDetected() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));

        tamper("UPDATE audit_event SET details = '{\"userId\":\"someone-else\",\"role\":\"OWNER\"}'::jsonb"
                + " WHERE company_id = ? AND seq = 2", company.id());

        JsonNode verification = owner.get(company.path("/audit-events/verification")).json();
        assertThat(verification.get("valid").asBoolean()).isFalse();
        assertThat(verification.get("firstInvalidSeq").asLong()).isEqualTo(2);
    }

    @Test
    void aDeletedEventIsDetected() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));

        tamper("DELETE FROM audit_event WHERE company_id = ? AND seq = 3", company.id());

        JsonNode verification = owner.get(company.path("/audit-events/verification")).json();
        assertThat(verification.get("valid").asBoolean()).isFalse();
        assertThat(verification.get("firstInvalidSeq").asLong()).isEqualTo(3);
    }

    @Test
    void aTruncatedChainIsDetected() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));

        int last = actionsOldestFirst(owner, company).size();

        tamper("DELETE FROM audit_event WHERE company_id = ? AND seq = " + last, company.id());

        JsonNode verification = owner.get(company.path("/audit-events/verification")).json();
        assertThat(verification.get("valid").asBoolean()).isFalse();
        assertThat(verification.get("firstInvalidSeq").asLong()).isEqualTo(last);
        assertThat(verification.get("checkedEvents").asLong()).isEqualTo(last - 1);
    }

    @Test
    void theRuntimeRoleCannotUpdateOrDeleteAuditEvents() throws SQLException {
        try (Connection app = TestDatabase.connectAsApp(); Statement statement = app.createStatement()) {
            statement.execute("SET app.system = 'true'");
            assertThatThrownBy(() -> statement.executeUpdate("UPDATE audit_event SET action = 'forged'"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM audit_event"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM audit_chain_head"))
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("permission denied");
        }
    }

    @Test
    void theRuntimeRoleCannotMoveAHeadButOneEventForward() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        long seq = owner.get(company.path("/audit-events?limit=1")).json().get("items").get(0).get("seq").asLong();

        try (Connection app = TestDatabase.connectAsApp()) {
            app.setAutoCommit(false);
            // Backwards, as SQL run as the runtime role in the company's context could try (L16).
            assertHeadRefused(app, company.id(),
                    "UPDATE audit_chain_head SET last_seq = last_seq - 2 WHERE company_id = ?");
            // Forward by one, but to an event that does not exist.
            assertHeadRefused(app, company.id(),
                    "UPDATE audit_chain_head SET last_seq = last_seq + 1, last_hash = '" + "e".repeat(64)
                            + "' WHERE company_id = ?");
            // In place, with another hash.
            assertHeadRefused(app, company.id(),
                    "UPDATE audit_chain_head SET last_hash = '" + "f".repeat(64) + "' WHERE company_id = ?");
            // Another chain.
            assertHeadRefused(app, company.id(), "UPDATE audit_chain_head SET company_id = NULL WHERE company_id = ?");
        }

        // The chain is intact and goes on.
        owner.patch(company.path(""), Map.of("name", unique("Still chained")));
        JsonNode verification = owner.get(company.path("/audit-events/verification")).json();
        assertThat(verification.get("valid").asBoolean()).isTrue();
        assertThat(owner.get(company.path("/audit-events?limit=1")).json().get("items").get(0).get("seq").asLong())
            .isGreaterThan(seq);
    }

    private static void assertHeadRefused(Connection app, UUID companyId, String sql) throws SQLException {
        try (PreparedStatement context = app.prepareStatement("SELECT set_config('app.company_id', ?, true)")) {
            context.setString(1, companyId.toString());
            context.execute();
        }
        try (PreparedStatement update = app.prepareStatement(sql)) {
            update.setObject(1, companyId);
            assertThatThrownBy(update::executeUpdate).as(sql)
                .isInstanceOf(SQLException.class)
                .hasMessageContaining("audit chain");
        }
        app.rollback();
    }

    /** The company's audit actions, oldest first. */
    private static List<String> actionsOldestFirst(ApiClient actor, TestCompany company) {
        List<String> actions = new ArrayList<>();
        actor.get(company.path("/audit-events?limit=200")).json().get("items")
            .forEach(event -> actions.addFirst(event.get("action").asString()));
        return actions;
    }

    /**
     * Tampering by someone with owner rights on the schema: even the owner is bound by FORCE ROW LEVEL SECURITY
     * (and there is no UPDATE/DELETE policy), so the attacker first switches it off.
     */
    private static void tamper(String sql, UUID companyId) throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner()) {
            owner.setAutoCommit(false);
            try (Statement ddl = owner.createStatement(); PreparedStatement change = owner.prepareStatement(sql)) {
                ddl.execute("ALTER TABLE audit_event NO FORCE ROW LEVEL SECURITY");
                change.setObject(1, companyId);
                assertThat(change.executeUpdate()).isEqualTo(1);
                ddl.execute("ALTER TABLE audit_event FORCE ROW LEVEL SECURITY");
            }
            owner.commit();
        }
    }
}
