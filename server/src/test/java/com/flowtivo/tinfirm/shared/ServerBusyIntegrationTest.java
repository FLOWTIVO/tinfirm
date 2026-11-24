package com.flowtivo.tinfirm.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.web.ServerBusy;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.ConnectionWatch;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.RefusedAuditRecords;
import com.flowtivo.tinfirm.support.TestConversations;

/**
 * The rule of what a request is told when it finds no free database connection, or when a step after a commit fails
 * (shared.web.ServerBusy): a read, and a write that saved nothing yet, are {@code 503 SERVER_BUSY} with
 * {@code Retry-After}; a write in which a change may already be saved is a 500 that says so, without
 * {@code Retry-After}; a read never hears that a change may be saved. The pool's refusal is the test fixture's
 * ({@link ConnectionWatch#timeOutAt}): the pool's own exception at the chosen place, with no other job or thread
 * competing for a small pool.
 */
class ServerBusyIntegrationTest extends IntegrationTest {

    private static final String SERVICE = "com.flowtivo.tinfirm.conversation.internal.ConversationService$$SpringCGLIB$$0";

    private static final String SESSION_SAVE = "org.springframework.session.jdbc.JdbcIndexedSessionRepository.save";

    private record Setup(TestCompany company, ApiClient member, UUID conversation) {
    }

    private Setup setup() {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiResponse agent = member.post(company.path("/agents"), Map.of("name", unique("Helper"), "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "", "systems", List.of(), "repositories", List.of(),
                "shareGroupIds", List.of()));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        UUID conversation = UUID.randomUUID();
        TestConversations.bare(company.id(), conversation, unique("c"),
                UUID.fromString(agent.json().get("id").asString()), memberPrincipal(company, company.userId("MEMBER")),
                "CLAUDE_CODE");
        return new Setup(company, member, conversation);
    }

    @Test
    void aReadThatFindsNoFreeConnectionIsToldTheServerIsBusy() throws Exception {
        Setup setup = setup();

        ApiResponse list;
        try (AutoCloseable busy = ConnectionWatch.timeOutAt(SERVICE + ".list")) {
            list = setup.member().get(setup.company().path("/conversations?limit=50"));
        }

        assertThat(list.status()).as("%s", list).isEqualTo(503);
        assertThat(list.problemCode()).isEqualTo("SERVER_BUSY");
        assertThat(list.header("Retry-After")).isEqualTo("2");
        assertThat(setup.member().get(setup.company().path("/conversations?limit=50")).status()).isEqualTo(200);
    }

    @Test
    void aWriteThatFindsNoFreeConnectionBeforeItSavedAnythingIsToldTheServerIsBusy() throws Exception {
        Setup setup = setup();

        ApiResponse ended;
        try (AutoCloseable busy = ConnectionWatch.timeOutAt(SERVICE + ".end")) {
            ended = setup.member().post(setup.company().path("/conversations/" + setup.conversation() + "/end"),
                    Map.of());
        }

        assertThat(ended.status()).as("%s", ended).isEqualTo(503);
        assertThat(ended.problemCode()).isEqualTo("SERVER_BUSY");
        assertThat(ended.header("Retry-After")).isEqualTo("2");
        assertThat(setup.member().get(setup.company().path("/conversations/" + setup.conversation())).json()
            .get("state").asString()).isNotEqualTo("ENDED");
    }

    /**
     * An instance admin's break-glass write: the access is recorded once the read before the call to the gateway
     * committed; the write after that call finds no free connection. The record stays, the write is not saved, and
     * the admin is told the server is busy: neither the record nor the read is a change of the request.
     */
    @Test
    void aBreakGlassWriteThatFindsNoFreeConnectionIsToldTheServerIsBusyWithItsAccessOnRecord() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiResponse put = owner.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED", "defaultBudgetUsd",
                25, "budgetPeriod", "30d"));
        assertThat(put.status()).as("%s", put).isEqualTo(200);
        ApiClient admin = admin();
        UUID adminId = UUID.fromString(admin.get("/api/me").json().get("id").asString());
        String service = "com.flowtivo.tinfirm.model.internal.ModelSettingsService.test";
        String audit = "com.flowtivo.tinfirm.company.internal.AdminAccessAudit";
        AutoCloseable[] busy = new AutoCloseable[1];
        // Once the access is being recorded, the request's later transactions find no connection.
        ConnectionWatch.plantAt(audit, () -> busy[0] = ConnectionWatch.timeOutAt(service, audit));

        ApiResponse tested;
        try {
            tested = admin.post(company.path("/model-settings/test"), null);
        }
        finally {
            if (busy[0] != null) {
                busy[0].close();
            }
        }

        assertThat(tested.status()).as("%s", tested).isEqualTo(503);
        assertThat(tested.problemCode()).isEqualTo("SERVER_BUSY");
        assertThat(tested.header("Retry-After")).isEqualTo("2");
        assertThat(auditEvents(owner, company, "admin.company.accessed"))
            .anySatisfy(event -> assertThat(event.get("actor").get("id").asString()).isEqualTo(adminId.toString()));
        assertThat(auditEvents(owner, company, "model_settings.changed"))
            .noneSatisfy(event -> assertThat(event.get("details").path("tested").asBoolean()).isTrue());
        assertThat(owner.get(company.path("/model-settings")).json().get("relayEnabled").asBoolean()).isFalse();
    }

    /** The session is written back after the change was committed: its save finding no connection is not "busy". */
    @Test
    void aWriteWhoseSessionSaveFindsNoConnectionAfterItsCommitIsToldTheChangeMayAlreadyBeSaved() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        String name = unique("Helper");

        ApiResponse created;
        try (AutoCloseable busy = ConnectionWatch.timeOutAt(SESSION_SAVE)) {
            created = member.post(company.path("/agents"), Map.of("name", name, "runtimes", List.of("CLAUDE_CODE"),
                    "instructions", "", "systems", List.of(), "repositories", List.of(), "shareGroupIds", List.of()));
        }

        assertThat(created.status()).as("%s", created).isEqualTo(500);
        assertThat(created.json().get("detail").asString()).isEqualTo(ServerBusy.SAVED_THEN_FAILED);
        assertThat(created.header("Retry-After")).isNull();
        assertThat(loginAs(company.email("MEMBER")).get(company.path("/agents")).body()).contains(name);
    }

    /** A GET whose transaction is not read-only, and whose record of the access fails after it: a failed read. */
    @Test
    void aReadInAReadWriteTransactionWhoseRecordFailsIsAFailedReadNeverSaved() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient admin = admin();
        UUID adminId = UUID.fromString(admin.get("/api/me").json().get("id").asString());

        ApiResponse settings;
        try (RefusedAuditRecords ignored = RefusedAuditRecords.of("admin.company.accessed", adminId)) {
            settings = admin.get(company.path("/model-settings"));
        }

        assertThat(settings.status()).as("%s", settings).isEqualTo(500);
        assertThat(settings.json().get("detail").asString()).isEqualTo("Internal server error");
        assertThat(settings.header("Retry-After")).isNull();
    }
}
