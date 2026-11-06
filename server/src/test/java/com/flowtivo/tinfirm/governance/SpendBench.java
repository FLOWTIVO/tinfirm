package com.flowtivo.tinfirm.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import com.flowtivo.tinfirm.governance.internal.SpendSync;
import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestRuns;

/**
 * What the spend tests share (TEST FIXTURE): a company whose bundled gateway passed its test, a paying service
 * identity and a company host; runs written by {@link TestRuns} that relay requests through the real relay to
 * {@link FakeLiteLlm}, whose answers report usage; a run's end published as the run module publishes it.
 */
abstract class SpendBench extends IntegrationTest {

    static final String MESSAGES = "/api/runner/v1/model/anthropic/v1/messages";

    static final String CHAT = "/api/runner/v1/model/openai/v1/chat/completions";

    static final String SONNET = "claude-sonnet-4-5";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    ModelRelay relay;

    @Autowired
    SystemTransactions transactions;

    @Autowired
    ApplicationEventPublisher publisher;

    @Autowired
    SpendSync sync;

    record Setup(TestCompany company, ApiClient owner, UUID payer, FakeLiteLlm.Key key, UUID hostId) {

        UUID companyId() {
            return company.id();
        }
    }

    /** A run of the setup that relayed: its id and its one-run token. */
    record Relayed(UUID runId, String token) {
    }

    @AfterEach
    void resetModelHandler() {
        litellm.resetModelHandler();
    }

    Setup setUp() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID payer = createServiceIdentity(owner, company, unique("bot"));
        FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, company, payer);
        ModelGateways.awaitStatus(owner, company, payer, "OK");
        UUID hostId = registerRunnerHost(owner, company.id(), unique("host"), "COMPANY").id();
        return new Setup(company, owner, payer, key, hostId);
    }

    /** A new run RUNNING on the setup's host, its relay opened in the format. */
    Relayed running(Setup setup, RunnerMessages.ModelAccessFormat format) {
        UUID runId = UUID.randomUUID();
        TestRuns.running(setup.companyId(), runId, 1, setup.hostId(), setup.payer());
        ModelRelay.RelayedRun run = new ModelRelay.RelayedRun(setup.companyId(), runId, 1, setup.hostId(),
                setup.payer(), null, null);
        String token = transactions.inCompany(setup.companyId(), () -> relay.open(run, format)).apiKey();
        return new Relayed(runId, token);
    }

    /** LiteLLM answers Anthropic's stream with this usage, as {@code message_start} and {@code message_delta} do. */
    void answerAnthropicStream(long input, long cacheRead, long cacheWrite, long output) {
        String stream = "event: message_start\n"
                + "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_1\",\"model\":\"" + SONNET
                + "-20250929\",\"usage\":{\"input_tokens\":" + input + ",\"cache_read_input_tokens\":" + cacheRead
                + ",\"cache_creation_input_tokens\":" + cacheWrite + ",\"output_tokens\":1}}}\n\n"
                + "event: content_block_delta\n"
                + "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hi\"}}\n\n"
                + "event: message_delta\n"
                + "data: {\"type\":\"message_delta\",\"usage\":{\"output_tokens\":" + output + "}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        litellm.onModel((exchange, request, key) -> {
            byte[] bytes = stream.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(bytes);
            }
        });
    }

    /** LiteLLM answers OpenAI's chat completion as one JSON object with its usage. */
    void answerOpenAiJson(long prompt, long cached, long completion) {
        litellm.onModel((exchange, request, key) -> FakeLiteLlm.json(exchange, 200, tools.jackson.databind.json.JsonMapper
            .builder().build().readTree("{\"id\":\"chatcmpl-1\",\"model\":\"gpt-5.4-2026\",\"choices\":[],"
                    + "\"usage\":{\"prompt_tokens\":" + prompt + ",\"completion_tokens\":" + completion
                    + ",\"prompt_tokens_details\":{\"cached_tokens\":" + cached + "}}}")));
    }

    HttpResponse<String> post(String path, String token, String model) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"" + model
                    + "\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"))
            .build();
        try {
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
            return response;
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** The run ends, and its end is published in a transaction of its company, as the run module does. */
    void end(UUID companyId, UUID runId) {
        TestRuns.ended(companyId, runId);
        transactions.runInCompany(companyId,
                () -> publisher.publishEvent(new RunEvents.RunEnded(companyId, runId, UUID.randomUUID(),
                        RunStatus.SUCCEEDED)));
    }

    /** The run's settlement is due now, its run having ended {@code endedAgo} ago. */
    static void due(UUID companyId, UUID runId, Duration endedAgo) {
        Instant ended = Instant.now().minus(endedAgo);
        sql(companyId, """
                UPDATE spend_settlement SET due_at = now() - interval '1 second', started_at = ?, ended_at = ?
                 WHERE run_id = ?""", java.sql.Timestamp.from(ended.minusSeconds(60)), java.sql.Timestamp.from(ended),
                runId);
    }

    static void sql(UUID companyId, String statement, Object... params) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            sql.update(statement, params);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static List<Map<String, Object>> rows(UUID companyId, String query, Object... params) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.rows(query, params);
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    static List<Map<String, Object>> spend(UUID companyId, UUID runId) {
        return rows(companyId, "SELECT * FROM run_spend WHERE run_id = ? ORDER BY model", runId);
    }

    static Map<String, Object> settlement(UUID companyId, UUID runId) {
        List<Map<String, Object>> rows = rows(companyId, "SELECT * FROM spend_settlement WHERE run_id = ?", runId);
        return rows.isEmpty() ? Map.of() : rows.getFirst();
    }

    /** Settles what is due now, as a worker's tick does. */
    int settle() {
        return sync.syncDue();
    }
}
