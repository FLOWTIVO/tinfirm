package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.RunnerFileRequests;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The bench of part 3's integration tests of its own rules (TEST FIXTURE ONLY): a company whose MEMBER is the person of
 * the conversations, the person's agent, conversations as bare rows ({@link TestConversations}) that the conversation
 * module reads as any other, turns written as rows ({@link TestRuns}) on a registered company runner host, whose ends
 * the tests hand to the hooks as {@code TurnEnded} does, and a runner host's own requests to its file endpoints.
 * {@code ConversationOutputsEndToEndTest} runs the same through started conversations and a fake runner host.
 */
abstract class OutputTestBench extends IntegrationTest {

    static final Duration WAIT = Duration.ofSeconds(20);

    static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    SystemTransactions transactions;

    @Autowired
    Conversations conversations;

    /** A company, its OWNER, the MEMBER who is the conversations' person, with their principal, and their agent. */
    record Bench(TestCompany company, ApiClient owner, ApiClient person, UUID personPrincipalId, UUID agentId,
            String agentName) {

        UUID companyId() {
            return company.id();
        }

        String path(String suffix) {
            return company.path(suffix);
        }
    }

    /** A conversation of the bench: its id and key. */
    record Conversation(UUID id, String key) {
    }

    Bench bench() {
        return bench(List.of());
    }

    /** A bench whose agent lists these systems (each {@code {systemId, tools?}}). */
    Bench bench(List<Map<String, Object>> systems) {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient person = loginAs(company.email("MEMBER"));
        String agentName = unique("Builder");
        return new Bench(company, owner, person, memberPrincipal(company, company.userId("MEMBER")),
                agent(person, company, agentName, systems), agentName);
    }

    /** An agent of the caller, listing these systems. */
    UUID agent(ApiClient caller, TestCompany company, String name, List<Map<String, Object>> systems) {
        ApiResponse agent = caller.post(company.path("/agents"), Map.of("name", name, "runtimes",
                List.of("CLAUDE_CODE"), "systems", systems));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        return UUID.fromString(agent.json().get("id").asString());
    }

    /** A new conversation of the bench's person with its agent. */
    Conversation conversation(Bench bench) {
        return conversation(bench, bench.agentId(), bench.personPrincipalId());
    }

    Conversation conversation(Bench bench, UUID agentId, UUID personPrincipalId) {
        UUID id = UUID.randomUUID();
        String key = "conv-" + id.toString().substring(0, 8);
        TestConversations.bare(bench.companyId(), id, key, agentId, personPrincipalId, "CLAUDE_CODE");
        return new Conversation(id, key);
    }

    String files(Bench bench, Conversation conversation) {
        return bench.path("/conversations/" + conversation.id() + "/attachments");
    }

    /** A company runner host of the bench; its token, for its requests. */
    RunnerHostRef host(Bench bench) {
        return registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
    }

    /** A turn of the conversation RUNNING on the host with the epoch. */
    UUID running(Bench bench, Conversation conversation, RunnerHostRef host, long epoch, int turn) {
        return running(bench, conversation, bench.agentId(), host, epoch, turn);
    }

    UUID running(Bench bench, Conversation conversation, UUID agentId, RunnerHostRef host, long epoch, int turn) {
        UUID runId = UUID.randomUUID();
        TestRuns.running(bench.companyId(), runId, epoch, host.id(), conversation.id(), conversation.key(), agentId,
                bench.personPrincipalId(), turn);
        return runId;
    }

    <T> T inCompany(Bench bench, Supplier<T> work) {
        return transactions.inCompany(bench.companyId(), work);
    }

    /** The conversation as part 1 shows it to the parts. */
    ConversationInfo conversationInfo(Bench bench, Conversation conversation) {
        return inCompany(bench, () -> conversations.find(bench.companyId(), conversation.id()).orElseThrow());
    }

    /** One column of the rows, as the runtime role reads them in the company. */
    static List<Object> column(Bench bench, String sql, Object... params) {
        try (TenantSql tenant = TenantSql.in(bench.companyId())) {
            return tenant.column(sql, params);
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** Writes as the runtime role in the company, and commits. */
    static int update(Bench bench, String sql, Object... params) {
        try (TenantSql tenant = TenantSql.in(bench.companyId())) {
            int updated = tenant.update(sql, params);
            tenant.commit();
            return updated;
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---- A runner host's requests --------------------------------------------------------------------------------

    /** {@code GET /api/runner/v1/runs/{runId}/attachments/{attachmentId}?epoch=} with the host's token. */
    HttpResponse<byte[]> hostDownload(@Nullable String token, UUID runId, UUID attachmentId, long epoch) {
        return RunnerFileRequests.download(port, token, runId, attachmentId, epoch);
    }

    /**
     * {@code POST /api/runner/v1/runs/{runId}/attachments?epoch=&artifact=&source=} with the host's token, as the runner
     * host sends it.
     *
     * @param query what follows {@code epoch=}, e.g. {@code &artifact=true&source=output}
     */
    HttpResponse<String> hostUpload(String token, UUID runId, long epoch, String query, String fileName,
            String contentType, byte[] content) {
        return RunnerFileRequests.upload(port, token, runId, epoch, query, fileName, contentType, content);
    }

    static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }
}
