package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.ConnectionWatch;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.RefusedAuditRecords;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * When OWNER's reads of other people's conversations, and an instance admin's break-glass access, are recorded: in a
 * transaction of their own once the request's transaction has released its connection, so that a request never holds
 * one pooled connection while it asks for a second. A read is on record before anything of it is returned, and a record
 * that cannot be written fails the read with nothing returned. An action keeps its own audit entry in its transaction,
 * and its read is recorded right after its commit: a record that fails then fails the request, and the action stays.
 * After a rollback too, a record that fails is the answer, in place of the request's own error.
 */
class OwnerReadRecordsIntegrationTest extends IntegrationTest {

    private static final String OWNER_READS = "com.flowtivo.tinfirm.conversation.internal.OwnerReads";

    private static final String BREAK_GLASS = "com.flowtivo.tinfirm.company.internal.AdminAccessAudit";

    private static final String READ = "conversation.read";

    private static final String ACCESSED = "admin.company.accessed";

    private record Setup(TestCompany company, ApiClient owner, ApiClient member, UUID agentId, UUID memberPrincipal) {

        UUID conversation(String key) {
            UUID id = UUID.randomUUID();
            TestConversations.bare(company.id(), id, key, agentId, memberPrincipal, "CLAUDE_CODE");
            return id;
        }
    }

    private Setup setup() {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiResponse agent = member.post(company.path("/agents"), Map.of("name", unique("Helper"), "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "", "systems", List.of(), "repositories", List.of(),
                "shareGroupIds", List.of()));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        return new Setup(company, loginAs(company.email("OWNER")), member,
                UUID.fromString(agent.json().get("id").asString()),
                memberPrincipal(company, company.userId("MEMBER")));
    }

    @Test
    void ownersReadsAreOnRecordWhenAnsweredAndAskForOneConnectionAtATime() {
        Setup setup = setup();
        UUID conversation = setup.conversation(unique("c"));

        ConnectionWatch.Mark mark = ConnectionWatch.mark();
        ApiResponse list = setup.owner().get(setup.company().path("/conversations"));
        ApiResponse read = setup.owner().get(setup.company().path("/conversations/" + conversation));

        assertThat(list.status()).as("%s", list).isEqualTo(200);
        assertThat(read.status()).as("%s", read).isEqualTo(200);
        List<JsonNode> records = auditEvents(setup.owner(), setup.company(), "conversation.read");
        assertThat(records).extracting(event -> event.get("details").get("what").asString())
            .containsExactly("conversation", "conversations");
        assertThat(mark.at(OWNER_READS)).as("second connections asked for").isZero();
    }

    @Test
    void aReadWhoseRecordCannotBeWrittenFailsWithNothingReturned() throws Exception {
        Setup setup = setup();
        String key = unique("secret");
        UUID conversation = setup.conversation(key);

        try (RefusedAuditRecords ignored = RefusedAuditRecords.of(READ, setup.company().userId("OWNER"))) {
            ApiResponse list = setup.owner().get(setup.company().path("/conversations"));
            ApiResponse read = setup.owner().get(setup.company().path("/conversations/" + conversation));

            assertThat(list.status()).as("%s", list).isEqualTo(500);
            assertThat(list.body()).doesNotContain(key);
            assertThat(list.json().has("items")).isFalse();
            assertThat(read.status()).as("%s", read).isEqualTo(500);
            assertThat(read.body()).doesNotContain(key);
            assertThat(read.json().has("agentId")).isFalse();
        }
        assertThat(auditEvents(setup.owner(), setup.company(), "conversation.read")).isEmpty();
        // The person's own reads are not recorded, and go on.
        assertThat(setup.member().get(setup.company().path("/conversations/" + conversation)).status())
            .isEqualTo(200);
    }

    @Test
    void anActionKeepsItsAuditEntryAndItsReadIsRecordedRightAfterItsCommit() {
        Setup setup = setup();
        UUID conversation = setup.conversation(unique("c"));

        ApiResponse ended = setup.owner().post(setup.company().path("/conversations/" + conversation + "/end"),
                Map.of());

        assertThat(ended.status()).as("%s", ended).isEqualTo(200);
        List<JsonNode> events = auditEvents(setup.owner(), setup.company()).stream()
            .filter(event -> conversation.toString().equals(event.get("targetId").asString()))
            .toList();
        // Newest first: the read, recorded after the commit of the end and its own entry.
        assertThat(events).extracting(event -> event.get("action").asString())
            .containsExactly("conversation.read", "conversation.ended");
    }

    @Test
    void aRecordThatFailsAfterTheActionsCommitFailsTheRequestAndTheActionStays() throws Exception {
        Setup setup = setup();
        UUID conversation = setup.conversation(unique("c"));

        ApiResponse ended;
        try (RefusedAuditRecords ignored = RefusedAuditRecords.of(READ, setup.company().userId("OWNER"))) {
            ended = setup.owner().post(setup.company().path("/conversations/" + conversation + "/end"), Map.of());
        }

        assertThat(ended.status()).as("%s", ended).isEqualTo(500);
        assertThat(setup.member().get(setup.company().path("/conversations/" + conversation)).json().get("state")
            .asString()).isEqualTo("ENDED");
        assertThat(auditEvents(setup.owner(), setup.company(), "conversation.ended")).hasSize(1);
        assertThat(auditEvents(setup.owner(), setup.company(), "conversation.read")).isEmpty();
    }

    @Test
    void afterARollbackTheReadStaysOnRecordAndARecordThatFailsIsTheAnswer() throws Exception {
        Setup setup = setup();
        UUID conversation = setup.conversation(unique("c"));
        assertThat(setup.owner().post(setup.company().path("/conversations/" + conversation + "/end"), Map.of())
            .status()).isEqualTo(200);
        int before = auditEvents(setup.owner(), setup.company(), "conversation.read").size();

        // An ended conversation is not cancelled: the action rolls back, its read stays on record.
        ApiResponse refused = setup.owner().post(setup.company().path("/conversations/" + conversation + "/cancel"),
                Map.of());
        assertThat(refused.status()).as("%s", refused).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("CONVERSATION_ENDED");
        assertThat(auditEvents(setup.owner(), setup.company(), "conversation.read")).hasSize(before + 1);

        // With a record that cannot be written, the record's failure is the answer, in place of the request's own:
        // a read is never answered without its record.
        try (RefusedAuditRecords ignored = RefusedAuditRecords.of(READ, setup.company().userId("OWNER"))) {
            ApiResponse again = setup.owner().post(setup.company().path("/conversations/" + conversation
                    + "/cancel"), Map.of());
            assertThat(again.status()).as("%s", again).isEqualTo(500);
            assertThat(again.problemCode()).isNull();
        }
    }

    @Test
    void anInstanceAdminsBreakGlassIsOnRecordAndAsksForOneConnectionAtATime() {
        TestCompany company = companyWithRoles();
        ApiClient admin = admin();

        ConnectionWatch.Mark mark = ConnectionWatch.mark();
        ApiResponse members = admin.get(company.path("/members"));

        assertThat(members.status()).as("%s", members).isEqualTo(200);
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, "admin.company.accessed")).isNotEmpty();
        assertThat(mark.at(BREAK_GLASS)).as("second connections asked for").isZero();
    }

    /**
     * A break-glass write (an OWNER appointed) keeps its own audit event in its transaction; a record of the access
     * that fails right after the commit fails the request, and the write stays.
     */
    @Test
    void aBreakGlassWriteWhoseRecordFailsAfterItsCommitStaysWithItsOwnAuditEvent() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient admin = admin();
        UUID adminId = UUID.fromString(admin.get("/api/me").json().get("id").asString());

        ApiResponse appointed;
        try (RefusedAuditRecords ignored = RefusedAuditRecords.of(ACCESSED, adminId)) {
            appointed = admin.patch(company.path("/members/" + company.userId("ADMIN")), Map.of("role", "OWNER"));
        }

        assertThat(appointed.status()).as("%s", appointed).isEqualTo(500);
        ApiClient owner = loginAs(company.email("OWNER"));
        assertThat(auditEvents(owner, company, "member.role.changed").getFirst().get("details").get("role")
            .asString()).isEqualTo("OWNER");
        assertThat(auditEvents(owner, company, ACCESSED)).isEmpty();
    }

    /** A break-glass read is on record before it is answered, and fails with nothing returned when its record fails. */
    @Test
    void aBreakGlassReadWhoseRecordFailsReturnsNothing() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient admin = admin();
        UUID adminId = UUID.fromString(admin.get("/api/me").json().get("id").asString());

        ApiResponse members;
        try (RefusedAuditRecords ignored = RefusedAuditRecords.of(ACCESSED, adminId)) {
            members = admin.get(company.path("/members"));
        }

        assertThat(members.status()).as("%s", members).isEqualTo(500);
        assertThat(members.body()).doesNotContain(company.email("MEMBER"));
        assertThat(members.json().has("items")).isFalse();
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, ACCESSED)).isEmpty();
        assertThat(admin.get(company.path("/members")).status()).isEqualTo(200);
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, ACCESSED)).hasSize(1);
    }

    /**
     * A break-glass write that is refused rolls back, and its access stays on record; when that record fails, its
     * failure is the answer in place of the refusal, so that no access goes unrecorded.
     */
    @Test
    void aRefusedBreakGlassWriteStaysOnRecordAndARecordThatFailsIsTheAnswer() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient admin = admin();
        UUID adminId = UUID.fromString(admin.get("/api/me").json().get("id").asString());

        // Nothing to test yet: the request is refused, its transaction rolls back.
        ApiResponse refused = admin.post(company.path("/model-settings/test"), null);
        assertThat(refused.status()).as("%s", refused).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("GATEWAY_NOT_CONFIGURED");
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, ACCESSED)).hasSize(1);

        ApiResponse again;
        try (RefusedAuditRecords ignored = RefusedAuditRecords.of(ACCESSED, adminId)) {
            again = admin.post(company.path("/model-settings/test"), null);
        }

        assertThat(again.status()).as("%s", again).isEqualTo(500);
        assertThat(again.problemCode()).isNull();
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, ACCESSED)).hasSize(1);
    }

    /** OWNER's read of another person's run's event stream is on record before the stream's first event is sent. */
    @Test
    void aRunsEventStreamIsOnRecordBeforeItsFirstEvent() throws Exception {
        Setup setup = setup();
        UUID conversation = setup.conversation(unique("c"));
        UUID run = UUID.randomUUID();
        TestRuns.ofConversation(setup.company().id(), run, conversation, unique("r"), setup.agentId(),
                setup.memberPrincipal(), 1, "SUCCEEDED");
        try (TenantSql sql = TenantSql.in(setup.company().id())) {
            sql.update("INSERT INTO run_event (run_id, seq, company_id, type, at, payload) "
                    + "VALUES (?, 1, ?, 'AGENT_MESSAGE', now(), CAST(? AS jsonb))", run, setup.company().id(),
                    "{\"text\":\"hello\"}");
            sql.commit();
        }

        HttpResponse<Stream<String>> stream = setup.owner().openEventStream(setup.company().path("/runs/" + run
                + "/events/stream"), Map.of());
        assertThat(stream.statusCode()).isEqualTo(200);
        try (Stream<String> lines = stream.body()) {
            assertThat(lines.filter(line -> line.startsWith("data:")).findFirst()).isPresent();
            // The first event is in: its read is on file already.
            try (TenantSql sql = TenantSql.in(setup.company().id())) {
                assertThat(sql.column("SELECT details->>'what' FROM audit_event WHERE action = ? AND target_id = ?",
                        READ, conversation.toString())).containsExactly("run-stream");
            }
        }
    }
}
