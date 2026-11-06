package com.flowtivo.tinfirm.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import com.flowtivo.tinfirm.runner.RunnerConnections;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * Files a person's client holds for a start (ADR-0084): {@code POST /api/companies/{companyId}/mcp/files} behind the
 * endpoint's bearer chain, and {@code start_conversation}'s {@code fileIds}, which move them into the conversation before
 * its first turn, as files its person added. The Outlook add-in's Origin under Community is {@code
 * EditionGatesIntegrationTest}'s.
 */
class McpFilesIntegrationTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    private final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    private RunnerConnections connections;

    @Value("${tinfirm.attachment.max-files-per-conversation}")
    private int maxFilesPerConversation;

    private record Bench(TestCompany company, ApiClient owner, ApiClient member, UUID memberPrincipal, UUID agentId) {

        UUID id() {
            return company.id();
        }
    }

    private Bench bench() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        ModelGateways.configureBundled(owner, company);
        ModelGateways.awaitKey(litellm, company, member);
        ModelGateways.awaitStatus(owner, company, member, "OK");
        ApiClient session = loginAs(company.email("MEMBER"));
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Support"));
        body.put("runtimes", List.of("CLAUDE_CODE"));
        body.put("instructions", "You help.");
        body.put("systems", List.of());
        body.put("repositories", List.of());
        body.put("shareGroupIds", List.of());
        ApiResponse agent = session.post(company.path("/agents"), body);
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        UUID agentId = UUID.fromString(agent.json().get("id").asString());
        TestSettings.nameModel(company.id(), agentId, member);
        return new Bench(company, owner, session, member, agentId);
    }

    private FakeRunner companyHost(Bench bench) {
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.helloV2AndWelcome(List.of(), List.of(), "0.2.0", 4);
        await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
        return runner;
    }

    private String token(ApiClient session, String scope) {
        ApiResponse response = session.post("/api/me/tokens", Map.of("currentPassword", PASSWORD,
                "name", "files", "scope", scope));
        assertThat(response.status()).as("%s", response).isEqualTo(201);
        return response.json().get("token").asString();
    }

    private ApiResponse upload(UUID companyId, String token, String name, String content) {
        return client().send("POST", "/api/companies/" + companyId + "/mcp/files",
                new ApiClient.Multipart(name, "text/plain", content.getBytes(StandardCharsets.UTF_8)),
                Map.of("Authorization", "Bearer " + token));
    }

    private ApiResponse start(UUID companyId, String token, UUID agentId, List<String> fileIds) {
        return start(companyId, token, agentId, fileIds, null);
    }

    /** {@code start_conversation} with the files, and the request's key when the client sends one. */
    private ApiResponse start(UUID companyId, String token, UUID agentId, List<String> fileIds,
            @Nullable String requestId) {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("agent", agentId.toString());
        arguments.put("message", "Summarise the files");
        arguments.put("fileIds", fileIds);
        if (requestId != null) {
            arguments.put("requestId", requestId);
        }
        Map<String, Object> call = Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of(
                "name", "start_conversation", "arguments", arguments));
        return client().send("POST", "/api/companies/" + companyId + "/mcp", call,
                Map.of("Authorization", "Bearer " + token));
    }

    @Test
    void aClientHoldsFilesThatItsStartMovesIntoTheConversation() {
        Bench bench = bench();
        String write = token(bench.member(), "WRITE");

        ApiResponse held = upload(bench.id(), write, "../invoice.txt", "Invoice 2026-114: 1 200 EUR");
        assertThat(held.status()).as("%s", held).isEqualTo(201);
        assertThat(held.json().get("fileName").asString()).isEqualTo("invoice.txt");
        assertThat(held.json().get("contentType").asString()).isEqualTo("text/plain");
        assertThat(held.json().get("sizeBytes").asLong()).isEqualTo(27);
        assertThat(Instant.parse(held.json().get("expiresAt").asString())).isAfter(Instant.now().plusSeconds(3500));
        String fileId = held.json().get("id").asString();

        // A token that may only read holds nothing; a credential is refused as an upload's is.
        ApiResponse readOnly = upload(bench.id(), token(bench.member(), "READ"), "notes.txt", "notes");
        assertThat(readOnly.status()).isEqualTo(403);
        assertThat(readOnly.problemCode()).isEqualTo("INSUFFICIENT_SCOPE");
        ApiResponse credential = upload(bench.id(), write, "deploy.yaml", "key: ghp_" + "Ab1".repeat(12));
        assertThat(credential.status()).isEqualTo(422);
        assertThat(credential.problemCode()).isEqualTo("CREDENTIAL_FOUND");
        // Another company's endpoint: as for any of its tools, not found.
        assertThat(upload(companyWithRoles().id(), write, "x.txt", "x").status()).isEqualTo(404);

        // The OWNER's file is not the member's to start with.
        ApiResponse owners = upload(bench.id(), token(bench.owner(), "WRITE"), "owner.txt", "the owner's");
        assertThat(owners.status()).isEqualTo(201);

        try (FakeRunner runner = companyHost(bench)) {
            ApiResponse notTheirs = start(bench.id(), write, bench.agentId(),
                    List.of(fileId, owners.json().get("id").asString()));
            assertThat(notTheirs.json().get("result").get("isError").asBoolean()).isTrue();
            assertThat(notTheirs.json().toString()).contains("VALIDATION_FAILED");

            ApiResponse started = start(bench.id(), write, bench.agentId(), List.of(fileId));
            JsonNode conversation = started.json().get("result").get("structuredContent");
            assertThat(conversation).as("%s", started).isNotNull();
            String conversationId = conversation.get("id").asString();
            runner.expect("run.assign");
            JsonNode files = bench.member().get(bench.company().path("/conversations/" + conversationId
                    + "/attachments")).json();
            assertThat(files).hasSize(1);
            assertThat(files.get(0).get("fileName").asString()).isEqualTo("invoice.txt");
            assertThat(auditEvents(bench.owner(), bench.company(), "attachment.uploaded")).singleElement()
                .satisfies(event -> {
                    assertThat(event.get("details").get("stagedFileId").asString()).isEqualTo(fileId);
                    assertThat(event.get("details").get("conversationId").asString()).isEqualTo(conversationId);
                });
            // Moved, not copied: the held file is gone, and a second start cannot name it.
            ApiResponse again = start(bench.id(), write, bench.agentId(), List.of(fileId));
            assertThat(again.json().toString()).contains("VALIDATION_FAILED");
        }
    }

    /**
     * ADR-0084, decision 2: a start replayed by its key moves none of the files it names, the first conversation
     * keeping the files of its own start; what the replay named stays held, for the client to release or let expire.
     */
    @Test
    void aStartSentAgainWithItsKeyAnswersTheFirstConversationAndMovesNoFiles() throws Exception {
        Bench bench = bench();
        String write = token(bench.member(), "WRITE");
        String first = upload(bench.id(), write, "invoice.txt", "Invoice 2026-114").json().get("id").asString();
        String second = upload(bench.id(), write, "invoice-again.txt", "Invoice 2026-114, again").json().get("id")
            .asString();
        String requestId = "gmail-click-" + UUID.randomUUID();
        try (FakeRunner runner = companyHost(bench)) {
            ApiResponse started = start(bench.id(), write, bench.agentId(), List.of(first), requestId);
            JsonNode conversation = started.json().get("result").get("structuredContent");
            assertThat(conversation).as("%s", started).isNotNull();
            runner.expect("run.assign");

            ApiResponse again = start(bench.id(), write, bench.agentId(), List.of(second), requestId);
            JsonNode replayed = again.json().get("result");
            assertThat(replayed.get("structuredContent").get("id").asString()).as("%s", again)
                .isEqualTo(conversation.get("id").asString());
            assertThat(replayed.get("content").get(0).get("text").asString()).contains("sent before");
            assertThat(bench.member().get(bench.company().path("/conversations/" + conversation.get("id").asString()
                    + "/attachments")).json().findValuesAsString("fileName")).containsExactly("invoice.txt");
            assertThat(auditEvents(bench.owner(), bench.company(), "attachment.uploaded")).hasSize(1);
        }
        try (TenantSql sql = TenantSql.in(bench.id())) {
            assertThat(sql.count("SELECT count(*) FROM staged_file WHERE id = ?", UUID.fromString(first))).isZero();
            assertThat(sql.count("SELECT count(*) FROM staged_file WHERE id = ?", UUID.fromString(second))).isOne();
        }
    }

    @Test
    void aHeldFileExpiresAfterAnHourAndIsSwept() throws Exception {
        Bench bench = bench();
        String write = token(bench.member(), "WRITE");
        String fileId = upload(bench.id(), write, "old.txt", "old").json().get("id").asString();
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE staged_file SET created_at = now() - interval '2 hours', expires_at = now() - interval "
                    + "'1 hour' WHERE id = ?", UUID.fromString(fileId));
            sql.commit();
        }
        try (FakeRunner runner = companyHost(bench)) {
            assertThat(start(bench.id(), write, bench.agentId(), List.of(fileId)).json().toString())
                .contains("VALIDATION_FAILED");
        }
        ApiResponse sweepable = upload(bench.id(), write, "new.txt", "new");
        assertThat(sweepable.status()).isEqualTo(201);
        try (TenantSql sql = TenantSql.in(bench.id())) {
            // The person's next upload deleted their expired file already.
            assertThat(sql.count("SELECT count(*) FROM staged_file WHERE id = ?", UUID.fromString(fileId))).isZero();
        }
    }

    private static final int RACERS = 4;

    /**
     * A hold counted the person's held files and inserted its own without a lock, so parallel holds one short of the
     * limit all passed the count. Each hold now counts under the person's advisory lock (CONVENTIONS §10). The holds
     * race released together while the person's principal row is held, so that a hold past its count waits at its
     * insert's foreign key: without the lock every one of them is held; with it exactly one is, and the others are
     * {@code 409 TOO_MANY_ATTACHMENTS}.
     */
    @Test
    void parallelHoldsOneShortOfThePersonsLimitHoldOne() throws Exception {
        TestCompany company = companyWithRoles();
        UUID person = memberPrincipal(company, company.userId("MEMBER"));
        String write = token(loginAs(company.email("MEMBER")), "WRITE");
        int maxFiles = maxFilesPerConversation;
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update("""
                    INSERT INTO staged_file (id, company_id, principal_id, user_id, file_name, content_type,
                                             size_bytes, sha256, content, created_at, expires_at)
                    SELECT gen_random_uuid(), ?, ?, ?, 'held-' || n || '.txt', 'text/plain', 1, repeat('0', 64),
                           '\\x30'::bytea, now(), now() + interval '1 hour'
                      FROM generate_series(1, ?) AS n""", company.id(), person, company.userId("MEMBER"),
                    maxFiles - 1);
            sql.commit();
        }
        ExecutorService threads = Executors.newFixedThreadPool(RACERS);
        try (TenantSql holder = TenantSql.in(company.id())) {
            holder.rows("SELECT id FROM principal WHERE id = ? FOR UPDATE", person);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ApiResponse>> holds = new ArrayList<>();
            for (int racer = 0; racer < RACERS; racer++) {
                String name = "race-" + racer + ".txt";
                holds.add(threads.submit(() -> {
                    start.await();
                    return upload(company.id(), write, name, "racing");
                }));
            }
            start.countDown();
            await().atMost(WAIT).until(() -> waitingHolds(company.id()) == RACERS);
            holder.commit();

            List<String> answers = new ArrayList<>();
            for (Future<ApiResponse> hold : holds) {
                ApiResponse answer = hold.get(60, TimeUnit.SECONDS);
                answers.add(answer.status() == 201 ? "201" : answer.status() + " " + answer.problemCode());
            }
            assertThat(answers).containsExactlyInAnyOrder("201", "409 TOO_MANY_ATTACHMENTS",
                    "409 TOO_MANY_ATTACHMENTS", "409 TOO_MANY_ATTACHMENTS");
        }
        finally {
            threads.shutdownNow();
        }
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM staged_file WHERE principal_id = ?", person))
                .isEqualTo(maxFiles);
        }
    }

    /** The sessions that wait as a hold does here: at the person's advisory lock, or at its insert's foreign key. */
    private static long waitingHolds(UUID companyId) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("""
                    SELECT count(*) FROM pg_stat_activity a
                     WHERE a.datname = current_database() AND a.wait_event_type = 'Lock'
                       AND (a.wait_event = 'advisory'
                            OR EXISTS (SELECT 1 FROM pg_locks l
                                        WHERE l.pid = a.pid AND l.granted AND l.mode = 'RowExclusiveLock'
                                          AND l.relation = 'staged_file'::regclass))""");
        }
    }
}
