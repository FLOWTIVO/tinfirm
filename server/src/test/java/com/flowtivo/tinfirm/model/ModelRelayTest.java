package com.flowtivo.tinfirm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The model relay (the package 2 contract, §6.1, §11; M7): a run's one-run token in place of any LiteLLM key, refused
 * once the run is no longer active on its host with its epoch; LiteLLM's fields removed from the body; a model the key
 * does not allow refused; the answer streamed through as it arrives; the size and concurrency limits; what LiteLLM's
 * refusals do to the key; and a new admin key of a company's own gateway, which turns the relay off until the gateway
 * passes its test again; and the runner host's own token next to the run's (open point 6): only the run's own host's,
 * required from a host of runner 0.3.0, and left out by an older one. The runs are rows written by {@link TestRuns},
 * which the run module's {@code RunModelTokens} answers for.
 */
@ExtendWith(OutputCaptureExtension.class)
class ModelRelayTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String MESSAGES = "/api/runner/v1/model/anthropic/v1/messages";

    private static final String RESPONSES = "/api/runner/v1/model/openai/v1/responses";

    /** The runner host's own token, which its model proxy sends from runner 0.3.0 on. */
    static final String HOST_TOKEN = "X-Tinfirm-Runner-Token";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    private ModelRelay relay;

    @Autowired
    private SystemTransactions transactions;

    @Autowired
    private ModelAccess modelAccess;

    /** A company whose bundled gateway passed its test, a service identity's key, and a company host with its token. */
    private record Setup(TestCompany company, ApiClient owner, UUID payer, FakeLiteLlm.Key key, UUID hostId,
            String hostToken) {
    }

    @AfterEach
    void resetModelHandler() {
        litellm.resetModelHandler();
        litellm.resetInfo();
    }

    private Setup setUp() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID payer = createServiceIdentity(owner, company, unique("bot"));
        FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, company, payer);
        ModelGateways.awaitStatus(owner, company, payer, "OK");
        RunnerHostRef host = registerRunnerHost(owner, company.id(), unique("host"), "COMPANY");
        return new Setup(company, owner, payer, key, host.id(), host.token());
    }

    /** Connects the host once with this runner version, which the server keeps as its latest {@code hello}'s. */
    private void hello(String hostToken, String runnerVersion) {
        try (FakeRunner runner = FakeRunner.connect(port, hostToken)) {
            runner.helloV2AndWelcome(List.of(), List.of(), runnerVersion, 2);
        }
    }

    private ModelRelay.RelayedRun run(Setup setup, UUID runId, long epoch, UUID hostId) {
        return new ModelRelay.RelayedRun(setup.company().id(), runId, epoch, hostId, setup.payer(),
                UUID.randomUUID(), UUID.randomUUID());
    }

    private RunnerMessages.ModelAccess open(Setup setup, ModelRelay.RelayedRun run,
            RunnerMessages.ModelAccessFormat format) {
        return transactions.inCompany(setup.company().id(), () -> relay.open(run, format));
    }

    /** Opens the relay for a new run on the setup's host, running there with epoch 1. */
    private String running(Setup setup, UUID runId) {
        TestRuns.running(setup.company().id(), runId, 1, setup.hostId(), setup.payer());
        return open(setup, run(setup, runId, 1, setup.hostId()), RunnerMessages.ModelAccessFormat.ANTHROPIC).apiKey();
    }

    private static String message(String model) {
        return "{\"model\":\"" + model + "\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
    }

    private HttpResponse<String> post(String path, String token, String body) {
        return post(path, token, body, Map.of());
    }

    private HttpResponse<String> post(String path, String token, String body, Map<String, String> headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        headers.forEach(request::header);
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** A request with each pair of {@code headers} as a header of its own, so that a name may repeat. */
    private HttpResponse<String> postWith(String path, String token, String body, String... headers) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token)
            .POST(HttpRequest.BodyPublishers.ofString(body));
        for (int i = 0; i < headers.length; i += 2) {
            request.header(headers[i], headers[i + 1]);
        }
        try {
            return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JsonNode json(HttpResponse<String> response) {
        return JSON.readTree(response.body());
    }

    @Test
    void theAssignmentCarriesTheRelayAndAOneRunTokenNeverAKey() {
        Setup setup = setUp();
        UUID runId = UUID.randomUUID();
        RunnerMessages.ModelAccess anthropic = open(setup, run(setup, runId, 1, setup.hostId()),
                RunnerMessages.ModelAccessFormat.ANTHROPIC);
        assertThat(anthropic.format()).isEqualTo(RunnerMessages.ModelAccessFormat.ANTHROPIC);
        assertThat(anthropic.baseUrl()).isEqualTo("http://localhost:8080/api/runner/v1/model/anthropic");
        assertThat(anthropic.apiKey()).matches("tfm_[A-Za-z0-9_-]{43}").isNotEqualTo(setup.key().key);
        assertThat(anthropic.toString()).doesNotContain(anthropic.apiKey());
        RunnerMessages.ModelAccess openAi = open(setup, run(setup, UUID.randomUUID(), 1, setup.hostId()),
                RunnerMessages.ModelAccessFormat.OPENAI);
        assertThat(openAi.baseUrl()).isEqualTo("http://localhost:8080/api/runner/v1/model/openai/v1");
        assertThatThrownBy(() -> open(setup, run(setup, UUID.randomUUID(), 1, setup.hostId()),
                RunnerMessages.ModelAccessFormat.BEDROCK))
            .isInstanceOf(IllegalArgumentException.class);
        // Only the token's hash is kept.
        assertThat(transactions.inCompany(setup.company().id(), () -> relay.usage(setup.company().id(), runId)))
            .isEqualTo(new ModelRelay.RelayUsage(0, 0, 0));
    }

    @Test
    void aRequestGoesToLiteLlmWithThePrincipalsKeyAndTheRunsTagsWithoutLiteLlmsFields() {
        Setup setup = setUp();
        UUID runId = UUID.randomUUID();
        TestRuns.running(setup.company().id(), runId, 1, setup.hostId(), setup.payer());
        ModelRelay.RelayedRun run = run(setup, runId, 1, setup.hostId());
        String token = open(setup, run, RunnerMessages.ModelAccessFormat.ANTHROPIC).apiKey();
        litellm.onModel((exchange, request, key) -> {
            exchange.getResponseHeaders().add("x-litellm-key-spend", "0.12");
            exchange.getResponseHeaders().add("x-litellm-model-api-base", "https://api.anthropic.com");
            exchange.getResponseHeaders().add("request-id", "req_1");
            FakeLiteLlm.json(exchange, 200, JSON.createObjectNode().put("type", "message").put("alias", key.alias));
        });
        String body = """
                {"model":"claude-sonnet-4-5","max_tokens":16,"stream":false,
                 "messages":[{"role":"user","content":"Hi","metadata":{"kept":"nested"}}],
                 "metadata":{"user_id":"x"},"api_base":"https://evil.example.com","base_url":"https://evil.example.com",
                 "api_key":"sk-other","custom_llm_provider":"openai","tags":["free"],"user":"someone",
                 "mock_response":"canned","litellm_metadata":{"a":1},"extra_body":{"b":2},"extra_headers":{"c":"d"},
                 "input_cost_per_token":0,"cache_read_input_token_cost":0,"_private":1,"aws_secret_access_key":"k",
                 "guardrail_config":{"presidio":{"pii_entities_config":{"CREDIT_CARD":"ALLOW"}}},
                 "langfuse_host":"https://logs.example.com","fallbacks":["gpt-5.4"],"num_retries":9,
                 "drop_params":false,"temperature":0.70000000000000001}""";

        HttpResponse<String> answer = post(MESSAGES + "?beta=true&api-version=1", token, body,
                Map.of("anthropic-version", "2023-06-01", "anthropic-beta", "fine-grained-tool-streaming-2025-05-14",
                        "x-litellm-api-key", "sk-smuggled", "x-api-key", "sk-smuggled", "Cookie", "a=b",
                        "x-litellm-tags", "free", "User-Agent", "claude-cli/2.1.0 (external, cli)"));

        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        assertThat(json(answer).get("alias").asString()).isEqualTo(setup.key().alias);
        assertThat(answer.headers().firstValue("request-id")).contains("req_1");
        assertThat(answer.headers().map().keySet()).noneMatch(name -> name.toLowerCase().startsWith("x-litellm"));
        FakeLiteLlm.Request forwarded = litellm.requests("/v1/messages").getLast();
        assertThat(forwarded.path()).isEqualTo("/v1/messages");
        assertThat(forwarded.header("authorization")).isEqualTo("Bearer " + setup.key().key);
        assertThat(forwarded.header("x-litellm-tags")).isEqualTo("run:" + runId + ",conversation:"
                + run.conversationId() + ",agent:" + run.agentId());
        assertThat(forwarded.header("anthropic-version")).isEqualTo("2023-06-01");
        assertThat(forwarded.header("anthropic-beta")).isEqualTo("fine-grained-tool-streaming-2025-05-14");
        assertThat(forwarded.header("user-agent")).isEqualTo("claude-cli/2.1.0 (external, cli)");
        assertThat(forwarded.header("x-litellm-api-key")).isNull();
        assertThat(forwarded.header("x-api-key")).isNull();
        assertThat(forwarded.header("cookie")).isNull();
        List<String> fields = new ArrayList<>();
        forwarded.json().propertyNames().forEach(fields::add);
        assertThat(fields).containsExactly("model", "max_tokens", "stream", "messages", "temperature", "drop_params");
        // The relay's own: LiteLLM drops what the model does not take, whatever the runtime.
        assertThat(forwarded.json().get("drop_params").asBoolean()).isTrue();
        assertThat(forwarded.json().get("messages").get(0).get("metadata").get("kept").asString()).isEqualTo("nested");
        // Numbers pass exactly as they came.
        assertThat(forwarded.text()).contains("\"temperature\":0.70000000000000001");
        // The key is LiteLLM's alone; the run's counters grow.
        assertThat(answer.body()).doesNotContain(setup.key().key);
        ModelRelay.RelayUsage usage = transactions.inCompany(setup.company().id(),
                () -> relay.usage(setup.company().id(), runId));
        assertThat(usage.requests()).isEqualTo(1);
        assertThat(usage.requestBytes()).isEqualTo(forwarded.body().length);
        assertThat(usage.responseBytes()).isEqualTo(answer.body().getBytes(StandardCharsets.UTF_8).length);
        // The key was revealed for the run, once.
        assertThat(post(MESSAGES, token, message("claude-sonnet-4-5")).statusCode()).isEqualTo(200);
        assertThat(auditEvents(setup.owner(), setup.company(), "secret.accessed"))
            .filteredOn(event -> event.get("details").path("purpose").asString().equals("run:" + runId))
            .hasSize(1);
        auditEvents(setup.owner(), setup.company()).forEach(event -> assertThat(event.toString())
            .doesNotContain(setup.key().key).doesNotContain(token));

        // The OpenAI root, and the models of both.
        String openAi = open(setup, run(setup, runId, 1, setup.hostId()), RunnerMessages.ModelAccessFormat.OPENAI)
            .apiKey();
        assertThat(post(RESPONSES, openAi, "{\"model\":\"claude-haiku-4-5\",\"input\":\"Hi\",\"user\":\"u\"}")
            .statusCode()).isEqualTo(200);
        FakeLiteLlm.Request responses = litellm.requests("/v1/responses").getLast();
        assertThat(responses.json().has("user")).isFalse();
        HttpResponse<String> models = get("/api/runner/v1/model/openai/v1/models", openAi);
        assertThat(models.statusCode()).as(models.body()).isEqualTo(200);
        assertThat(litellm.requests("/v1/models").getLast().header("authorization"))
            .isEqualTo("Bearer " + setup.key().key);
    }

    /**
     * The provider is never a relay the agent directs and never runs a tool itself (M1 and D8 of the security review of
     * 2026-10-27; ADR-0108): a tool the provider would run, every web search among them, and the MCP connector are
     * refused before LiteLLM with what they asked named, only the known beta values pass, and no model setting lets a
     * provider's tool through.
     */
    @Test
    void aToolTheProviderWouldRunIsAlwaysRefused() {
        Setup setup = setUp();
        String token = running(setup, UUID.randomUUID());
        int before = litellm.requests("/v1/messages").size();
        String search = "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,\"tools\":[{\"type\":"
                + "\"web_search_20250305\",\"name\":\"web_search\"}],"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
        String fetch = search.replace("web_search_20250305", "web_fetch_20250910");
        String dynamic = search.replace("web_search_20250305", "web_search_20260209");

        HttpResponse<String> refused = post(MESSAGES, token, search);
        assertThat(refused.statusCode()).as(refused.body()).isEqualTo(400);
        assertThat(json(refused).get("error").get("type").asString()).isEqualTo("invalid_request_error");
        assertThat(json(refused).get("error").get("message").asString()).contains("`web_search_20250305`");
        assertThat(post(MESSAGES, token, fetch).statusCode()).isEqualTo(400);
        assertThat(post(MESSAGES, token, dynamic).statusCode()).isEqualTo(400);
        UUID openAiRun = UUID.randomUUID();
        TestRuns.running(setup.company().id(), openAiRun, 1, setup.hostId(), setup.payer());
        String openAi = open(setup, run(setup, openAiRun, 1, setup.hostId()), RunnerMessages.ModelAccessFormat.OPENAI)
            .apiKey();
        String mcp = "{\"model\":\"claude-haiku-4-5\",\"input\":\"Hi\",\"tools\":[{\"type\":\"mcp\","
                + "\"server_label\":\"x\",\"server_url\":\"https://evil.example.com/mcp\"}]}";
        String openAiSearch = "{\"model\":\"claude-haiku-4-5\",\"input\":\"Hi\",\"tools\":[{\"type\":"
                + "\"web_search\"}]}";
        assertThat(post(RESPONSES, openAi, mcp).statusCode()).isEqualTo(400);
        assertThat(post(RESPONSES, openAi, openAiSearch).statusCode()).isEqualTo(400);

        // The MCP connector is refused, never removed silently: the caller learns why.
        String connector = "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,\"mcp_servers\":[{\"type\":\"url\","
                + "\"url\":\"https://evil.example.com/mcp\",\"name\":\"x\"}],\"tools\":[{\"name\":\"Bash\","
                + "\"input_schema\":{\"type\":\"object\"}}],\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
        HttpResponse<String> mcpConnector = post(MESSAGES, token, connector);
        assertThat(mcpConnector.statusCode()).as(mcpConnector.body()).isEqualTo(400);
        assertThat(json(mcpConnector).get("error").get("message").asString()).contains("`mcp_servers`");
        assertThat(litellm.requests("/v1/messages")).hasSize(before);
        assertThat(litellm.requests("/v1/responses")).noneMatch(request -> request.text().contains("evil.example.com"));

        // The runtime's own tools pass, and only the known betas reach LiteLLM.
        String bash = "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,\"tools\":[{\"name\":\"Bash\","
                + "\"input_schema\":{\"type\":\"object\"}}],\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";
        HttpResponse<String> passed = postWith(MESSAGES, token, bash, "anthropic-beta",
                "mcp-client-2025-04-04, fine-grained-tool-streaming-2025-05-14", "anthropic-beta",
                "code-execution-2025-08-25,interleaved-thinking-2025-05-14");
        assertThat(passed.statusCode()).as(passed.body()).isEqualTo(200);
        FakeLiteLlm.Request forwarded = litellm.requests("/v1/messages").getLast();
        assertThat(forwarded.json().get("tools").get(0).get("name").asString()).isEqualTo("Bash");
        assertThat(forwarded.header("anthropic-beta"))
            .isEqualTo("fine-grained-tool-streaming-2025-05-14,interleaved-thinking-2025-05-14");
        assertThat(post(MESSAGES, token, message("claude-sonnet-4-5"),
                Map.of("anthropic-beta", "mcp-client-2025-04-04")).statusCode()).isEqualTo(200);
        assertThat(litellm.requests("/v1/messages").getLast().header("anthropic-beta")).isNull();

        // The model settings have no switch for it: an old client's providerTools changes nothing.
        assertThat(setup.owner().get(setup.company().path("/model-settings")).json().has("providerTools")).isFalse();
        ApiResponse put = setup.owner().put(setup.company().path("/model-settings"),
                Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 25, "providerTools", "WEB_SEARCH"));
        assertThat(put.status()).as("%s", put).isEqualTo(200);
        assertThat(auditEvents(setup.owner(), setup.company(), "model_settings.changed"))
            .noneSatisfy(event -> assertThat(event.get("details").has("providerTools")).isTrue());
        assertThat(post(MESSAGES, token, search).statusCode()).isEqualTo(400);
        assertThat(post(RESPONSES, openAi, openAiSearch).statusCode()).isEqualTo(400);
    }

    private HttpResponse<String> get(String path, String token) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
            .header("Authorization", "Bearer " + token)
            .GET()
            .build();
        try {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (IOException | InterruptedException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void theTokenIsRefusedOnceItsRunIsNoLongerActiveOnItsHostWithItsEpoch() {
        Setup setup = setUp();
        UUID runId = UUID.randomUUID();
        UUID otherHost = registerRunnerHost(setup.owner(), setup.company().id(), unique("other"), "COMPANY").id();
        String token = open(setup, run(setup, runId, 1, setup.hostId()), RunnerMessages.ModelAccessFormat.ANTHROPIC)
            .apiKey();
        String body = message("claude-sonnet-4-5");
        int before = litellm.requests("/v1/messages").size();

        // Not running yet; then running on its host with its epoch.
        assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(401);
        TestRuns.running(setup.company().id(), runId, 1, setup.hostId(), setup.payer());
        assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(200);
        // Another epoch of the run (it was reassigned), or the run on another host: the token is refused.
        TestRuns.running(setup.company().id(), runId, 2, setup.hostId(), setup.payer());
        HttpResponse<String> wrongEpoch = post(MESSAGES, token, body);
        assertThat(wrongEpoch.statusCode()).isEqualTo(401);
        assertThat(json(wrongEpoch).get("error").get("type").asString()).isEqualTo("authentication_error");
        TestRuns.running(setup.company().id(), runId, 1, otherHost, setup.payer());
        assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(401);
        // The run ended.
        TestRuns.running(setup.company().id(), runId, 1, setup.hostId(), setup.payer());
        assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(200);
        TestRuns.ended(setup.company().id(), runId);
        assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(401);
        // A reassignment revokes the earlier token, though the old binding were active.
        TestRuns.running(setup.company().id(), runId, 1, setup.hostId(), setup.payer());
        String again = open(setup, run(setup, runId, 1, setup.hostId()), RunnerMessages.ModelAccessFormat.ANTHROPIC)
            .apiKey();
        assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(401);
        assertThat(post(MESSAGES, again, body).statusCode()).isEqualTo(200);
        // Revoked at the run's end.
        transactions.runInCompany(setup.company().id(), () -> relay.revoke(setup.company().id(), runId));
        assertThat(post(MESSAGES, again, body).statusCode()).isEqualTo(401);
        // Not on the other format's root, without a token, or with another.
        TestRuns.running(setup.company().id(), runId, 1, setup.hostId(), setup.payer());
        String third = open(setup, run(setup, runId, 1, setup.hostId()), RunnerMessages.ModelAccessFormat.ANTHROPIC)
            .apiKey();
        assertThat(post(RESPONSES, third, "{\"model\":\"claude-sonnet-4-5\",\"input\":\"Hi\"}").statusCode())
            .isEqualTo(401);
        assertThat(post(MESSAGES, null, body).statusCode()).isEqualTo(401);
        assertThat(post(MESSAGES, "tfm_" + "A".repeat(43), body).statusCode()).isEqualTo(401);
        assertThat(post(MESSAGES, setup.key().key, body).statusCode()).isEqualTo(401);
        assertThat(post(MESSAGES, third, body).statusCode()).isEqualTo(200);
        // Only the accepted requests reached LiteLLM.
        assertThat(litellm.requests("/v1/messages")).hasSize(before + 4);
        // Nothing else is served under the relay's root.
        assertThat(post("/api/runner/v1/model/anthropic/v1/complete", third, body).statusCode()).isEqualTo(404);
        assertThat(get(MESSAGES, third).statusCode()).isEqualTo(405);
    }

    @Test
    void aRunsTokenPassesWithItsOwnHostsTokenAndNeverWithAnotherHosts(CapturedOutput output) {
        Setup setup = setUp();
        hello(setup.hostToken(), "0.3.0");
        RunnerHostRef other = registerRunnerHost(setup.owner(), setup.company().id(), unique("other"), "COMPANY");
        TestCompany elsewhere = companyWithRoles();
        RunnerHostRef foreign = registerRunnerHost(loginAs(elsewhere.email("OWNER")), elsewhere.id(), unique("foreign"),
                "COMPANY");
        UUID runId = UUID.randomUUID();
        String token = running(setup, runId);
        String body = message("claude-sonnet-4-5");
        int before = litellm.requests("/v1/messages").size();

        // The run's token with its own host's token: through, and the host's token stays with the relay.
        HttpResponse<String> passed = post(MESSAGES, token, body, Map.of(HOST_TOKEN, setup.hostToken()));
        assertThat(passed.statusCode()).as(passed.body()).isEqualTo(200);
        FakeLiteLlm.Request forwarded = litellm.requests("/v1/messages").getLast();
        assertThat(forwarded.header("x-tinfirm-runner-token")).isNull();
        assertThat(forwarded.text()).doesNotContain(setup.hostToken());

        // A copied run token with another host's token: of the company, of another company, unknown, malformed, or
        // the header twice. Each is the same 401.
        for (String hostToken : List.of(other.token(), foreign.token(), "tfr_" + "A".repeat(43), "", "Bearer "
                + setup.hostToken(), token)) {
            HttpResponse<String> refused = post(MESSAGES, token, body, Map.of(HOST_TOKEN, hostToken));
            assertThat(refused.statusCode()).as(hostToken).isEqualTo(401);
            assertThat(json(refused).get("error").get("type").asString()).isEqualTo("authentication_error");
        }
        assertThat(postWith(MESSAGES, token, body, HOST_TOKEN, setup.hostToken(), HOST_TOKEN, setup.hostToken())
            .statusCode()).isEqualTo(401);
        assertThat(postWith(MESSAGES, token, body, HOST_TOKEN, other.token(), HOST_TOKEN, setup.hostToken())
            .statusCode()).isEqualTo(401);
        // The host's token is no run token.
        assertThat(post(MESSAGES, setup.hostToken(), body, Map.of(HOST_TOKEN, setup.hostToken())).statusCode())
            .isEqualTo(401);
        // Its own host's token does not revive a run that is no longer active there.
        TestRuns.running(setup.company().id(), runId, 1, other.id(), setup.payer());
        assertThat(post(MESSAGES, token, body, Map.of(HOST_TOKEN, setup.hostToken())).statusCode()).isEqualTo(401);
        assertThat(post(MESSAGES, token, body, Map.of(HOST_TOKEN, other.token())).statusCode()).isEqualTo(401);
        TestRuns.running(setup.company().id(), runId, 1, setup.hostId(), setup.payer());
        assertThat(post(MESSAGES, token, body, Map.of(HOST_TOKEN, setup.hostToken())).statusCode()).isEqualTo(200);

        assertThat(litellm.requests("/v1/messages")).hasSize(before + 2);
        assertThat(output.getAll()).contains("Refused a model request with the token of run " + runId)
            .doesNotContain(token)
            .doesNotContain(setup.hostToken())
            .doesNotContain(other.token())
            .doesNotContain(foreign.token());
    }

    @Test
    void withoutItsHostsTokenANewHostsRunIsRefusedAndAnOlderHostsIsAccepted(CapturedOutput output) {
        Setup setup = setUp();
        String body = message("claude-sonnet-4-5");
        // A host of runner 0.3.0 always sends its token: a run's token without it is a copy, from anywhere.
        hello(setup.hostToken(), "0.3.0");
        String token = running(setup, UUID.randomUUID());
        HttpResponse<String> refused = post(MESSAGES, token, body);
        assertThat(refused.statusCode()).isEqualTo(401);
        assertThat(json(refused).get("error").get("type").asString()).isEqualTo("authentication_error");
        assertThat(post(MESSAGES, token, body, Map.of(HOST_TOKEN, setup.hostToken())).statusCode()).isEqualTo(200);

        // An older host, of 0.2.0 (gateway runs, no host token): accepted without it, as before, and warned about once
        // a day; with its own token it is checked all the same.
        RunnerHostRef older = registerRunnerHost(setup.owner(), setup.company().id(), unique("older"), "COMPANY");
        hello(older.token(), "0.2.0");
        UUID olderRun = UUID.randomUUID();
        TestRuns.running(setup.company().id(), olderRun, 1, older.id(), setup.payer());
        String olderToken = open(setup, run(setup, olderRun, 1, older.id()), RunnerMessages.ModelAccessFormat.ANTHROPIC)
            .apiKey();
        assertThat(post(MESSAGES, olderToken, body).statusCode()).isEqualTo(200);
        assertThat(post(MESSAGES, olderToken, body).statusCode()).isEqualTo(200);
        assertThat(post(MESSAGES, olderToken, body, Map.of(HOST_TOKEN, older.token())).statusCode()).isEqualTo(200);
        assertThat(post(MESSAGES, olderToken, body, Map.of(HOST_TOKEN, setup.hostToken())).statusCode())
            .isEqualTo(401);
        String warning = "Runner host " + older.id() + " (runner 0.2.0) sends the model requests of its runs without "
                + "its own token";
        assertThat(output.getAll().split(java.util.regex.Pattern.quote(warning), -1)).hasSize(2);
        assertThat(output.getAll()).doesNotContain("Runner host " + setup.hostId() + " (runner")
            .doesNotContain(olderToken)
            .doesNotContain(older.token());

        // The latest hello decides: once the host runs 0.3.0, its runs need its token.
        hello(older.token(), "0.3.0");
        UUID upgradedRun = UUID.randomUUID();
        TestRuns.running(setup.company().id(), upgradedRun, 1, older.id(), setup.payer());
        String upgraded = open(setup, run(setup, upgradedRun, 1, older.id()),
                RunnerMessages.ModelAccessFormat.ANTHROPIC).apiKey();
        assertThat(post(MESSAGES, upgraded, body).statusCode()).isEqualTo(401);
        assertThat(post(MESSAGES, upgraded, body, Map.of(HOST_TOKEN, older.token())).statusCode()).isEqualTo(200);
    }

    @Test
    void aStaleAssignmentsTokenNeverRevokesALaterAssignments() {
        Setup setup = setUp();
        UUID runId = UUID.randomUUID();
        String body = message("claude-sonnet-4-5");
        // The run was assigned again (epoch 3) and runs with that assignment's token.
        TestRuns.running(setup.company().id(), runId, 3, setup.hostId(), setup.payer());
        String current = open(setup, run(setup, runId, 3, setup.hostId()), RunnerMessages.ModelAccessFormat.ANTHROPIC)
            .apiKey();
        assertThat(post(MESSAGES, current, body).statusCode()).isEqualTo(200);

        // A delivery of its first assignment, prepared slowly, opens its token only now: the later one still answers.
        String stale = open(setup, run(setup, runId, 1, setup.hostId()), RunnerMessages.ModelAccessFormat.ANTHROPIC)
            .apiKey();
        assertThat(post(MESSAGES, current, body).statusCode()).isEqualTo(200);
        assertThat(post(MESSAGES, stale, body).statusCode()).isEqualTo(401);
        // Its recording finds it stale and revokes its own epoch's token, and that one only.
        transactions.runInCompany(setup.company().id(), () -> relay.revoke(setup.company().id(), runId, 1));
        assertThat(post(MESSAGES, current, body).statusCode()).isEqualTo(200);
        TestRuns.running(setup.company().id(), runId, 1, setup.hostId(), setup.payer());
        assertThat(post(MESSAGES, stale, body).statusCode()).as("revoked, though its epoch were current")
            .isEqualTo(401);

        // A later assignment revokes the earlier epochs' tokens.
        TestRuns.running(setup.company().id(), runId, 5, setup.hostId(), setup.payer());
        String next = open(setup, run(setup, runId, 5, setup.hostId()), RunnerMessages.ModelAccessFormat.ANTHROPIC)
            .apiKey();
        assertThat(post(MESSAGES, next, body).statusCode()).isEqualTo(200);
        TestRuns.running(setup.company().id(), runId, 3, setup.hostId(), setup.payer());
        assertThat(post(MESSAGES, current, body).statusCode()).isEqualTo(401);
    }

    /**
     * A sync that read the key at LiteLLM before the relay heard from LiteLLM that its budget is spent records after
     * the relay: it keeps the relay's {@code BUDGET_SPENT} instead of putting back the {@code OK} it read, and the sync
     * that the relay asked for reads the key again. Were that sync to write {@code OK} back without a word (it had read
     * {@code OK}), the dispatcher would take the key as usable and start the payer's waiting runs on a spent budget,
     * until the relay's sync announced and audited the spent budget a second time.
     */
    @Test
    void aSyncThatReadTheKeyBeforeTheRelaySawItsBudgetSpentKeepsTheSpentBudget() throws Exception {
        Setup setup = setUp();
        UUID companyId = setup.company().id();
        String token = running(setup, UUID.randomUUID());
        String keyId = setup.key().token;
        // A sync reads the key while its budget is not spent yet, and LiteLLM's answer is slow to arrive.
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch answer = new CountDownLatch(1);
        litellm.beforeInfoAnswer(id -> {
            if (id.equals(keyId) && read.getCount() > 0) {
                read.countDown();
                try {
                    answer.await(60, TimeUnit.SECONDS);
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        modelAccess.refresh(companyId, setup.payer());
        assertThat(read.await(30, TimeUnit.SECONDS)).isTrue();

        // Meanwhile the budget is spent, and the relay hears so from LiteLLM.
        setup.key().spend = setup.key().maxBudget;
        assertThat(post(MESSAGES, token, message("claude-sonnet-4-5")).statusCode()).isEqualTo(429);
        ModelGateways.awaitStatus(setup.owner(), setup.company(), setup.payer(), "BUDGET_SPENT");
        long reads = infoReads(keyId);

        // The slow answer arrives: that sync records after the relay, and the one the relay asked for reads the key
        // again after it.
        answer.countDown();
        await().atMost(ModelGateways.WAIT).until(() -> infoReads(keyId) > reads && leaseFree(companyId, setup.payer()));
        ModelGateways.awaitStatus(setup.owner(), setup.company(), setup.payer(), "BUDGET_SPENT");
        await().atMost(ModelGateways.WAIT).untilAsserted(() -> assertThat(RecordedEvents
            .of(companyId, ModelEvents.ModelKeyStatusChanged.class)
            .stream()
            .filter(changed -> changed.principalId().equals(setup.payer()))
            .map(ModelEvents.ModelKeyStatusChanged::status)
            .toList()).as("the key's statuses as announced").endsWith("BUDGET_SPENT"));
        List<String> announced = RecordedEvents.of(companyId, ModelEvents.ModelKeyStatusChanged.class)
            .stream()
            .filter(changed -> changed.principalId().equals(setup.payer()))
            .map(ModelEvents.ModelKeyStatusChanged::status)
            .toList();
        assertThat(announced.subList(announced.indexOf("BUDGET_SPENT"), announced.size()))
            .as("announced after the relay's BUDGET_SPENT").containsOnly("BUDGET_SPENT").hasSize(1);
        assertThat(auditEvents(setup.owner(), setup.company(), "model_key.budget_spent")).hasSize(1);
    }

    /** The {@code /key/info} reads of the key at LiteLLM so far. */
    private long infoReads(String keyId) {
        return litellm.requests("/key/info").stream().filter(request -> request.path().contains(keyId)).count();
    }

    /** Whether no sync holds the principal's key row. */
    private static boolean leaseFree(UUID companyId, UUID principalId) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM principal_model_key WHERE principal_id = ? "
                    + "AND sync_lease_until IS NULL", principalId) == 1;
        }
    }

    @Test
    void aModelTheKeyDoesNotAllowIsRefusedBeforeLiteLlm() {
        Setup setup = setUp();
        String token = running(setup, UUID.randomUUID());
        int before = litellm.requests("/v1/messages").size();

        HttpResponse<String> refused = post(MESSAGES, token, message("gpt-5.4"));
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(json(refused).get("type").asString()).isEqualTo("error");
        assertThat(json(refused).get("error").get("type").asString()).isEqualTo("permission_error");
        assertThat(post(MESSAGES, token, "{\"max_tokens\":1}").statusCode()).isEqualTo(400);
        assertThat(post(MESSAGES, token, "[1]").statusCode()).isEqualTo(400);
        assertThat(post(MESSAGES, token, "{\"model\":\"claude-sonnet-4-5\",\"model\":\"gpt-5.4\"}").statusCode())
            .isEqualTo(400);
        assertThat(post(MESSAGES, token, "{\"model\":\"claude-sonnet-4-5\"} {}").statusCode()).isEqualTo(400);
        assertThat(litellm.requests("/v1/messages")).hasSize(before);

        // An override that allows it, then every model.
        assertThat(setup.owner().patch(setup.company().path("/principals/" + setup.payer() + "/model-access"),
                Map.of("models", List.of("gpt-5.4"))).status()).isEqualTo(200);
        await().atMost(ModelGateways.WAIT).until(() -> setup.key().models.equals(List.of("gpt-5.4")));
        assertThat(post(MESSAGES, token, message("gpt-5.4")).statusCode()).isEqualTo(200);
        assertThat(post(MESSAGES, token, message("claude-sonnet-4-5")).statusCode()).isEqualTo(403);
        assertThat(setup.owner().patch(setup.company().path("/principals/" + setup.payer() + "/model-access"),
                Map.of("models", List.of())).status()).isEqualTo(200);
        await().atMost(ModelGateways.WAIT).until(() -> setup.key().models.isEmpty());
        assertThat(post(MESSAGES, token, message("any-model")).statusCode()).isEqualTo(200);
    }

    @Test
    void theAnswerStreamsThroughAsItArrives() throws Exception {
        Setup setup = setUp();
        String token = running(setup, UUID.randomUUID());
        CountDownLatch firstSeen = new CountDownLatch(1);
        litellm.onModel((exchange, request, key) -> {
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("event: message_start\ndata: {\"part\":1}\n\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                // The second part only once the runner host saw the first: a relay that buffered never gets here.
                if (!firstSeen.await(20, TimeUnit.SECONDS)) {
                    throw new IOException("The first part never reached the client");
                }
                out.write("event: message_stop\ndata: {\"part\":2}\n\n".getBytes(StandardCharsets.UTF_8));
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        });
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + MESSAGES))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(message("claude-sonnet-4-5").replace("{", "{\"stream\":true,")))
            .build();
        HttpResponse<InputStream> answer = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("content-type")).contains("text/event-stream");
        try (InputStream in = answer.body()) {
            StringBuilder seen = new StringBuilder();
            byte[] buffer = new byte[1024];
            while (!seen.toString().contains("\"part\":1")) {
                int read = in.read(buffer);
                assertThat(read).as("the first part before the end").isPositive();
                seen.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
            }
            assertThat(seen.toString()).doesNotContain("\"part\":2");
            firstSeen.countDown();
            seen.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            assertThat(seen.toString()).contains("event: message_stop").endsWith("data: {\"part\":2}\n\n");
        }
    }

    @Test
    void aBodyOverSixtyFourMebibytesIsRefused() throws Exception {
        Setup setup = setUp();
        String token = running(setup, UUID.randomUUID());
        int before = litellm.requests("/v1/messages").size();
        byte[] body = new byte[64 * 1024 * 1024 + 1];
        Arrays.fill(body, (byte) ' ');
        // Without a length, as a stream: the relay reads up to the limit and refuses.
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + MESSAGES))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofByteArray(body)))
            .build();
        HttpResponse<String> refused = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(refused.statusCode()).isEqualTo(413);
        assertThat(JSON.readTree(refused.body()).get("error").get("type").asString()).isEqualTo("request_too_large");
        assertThat(litellm.requests("/v1/messages")).hasSize(before);
        // At the limit it passes: spaces around the JSON.
        byte[] json = message("claude-sonnet-4-5").getBytes(StandardCharsets.UTF_8);
        byte[] atLimit = new byte[64 * 1024 * 1024];
        Arrays.fill(atLimit, (byte) ' ');
        System.arraycopy(json, 0, atLimit, 0, json.length);
        HttpRequest passes = HttpRequest.newBuilder(URI.create("http://localhost:" + port + MESSAGES))
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofByteArray(atLimit))
            .build();
        assertThat(http.send(passes, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
    }

    @Test
    void aRunAndAHostHaveAtMostSixteenRequestsPerSlotAtOnce() throws Exception {
        Setup setup = setUp();
        UUID runId = UUID.randomUUID();
        String token = running(setup, runId);
        UUID otherRun = UUID.randomUUID();
        String other = running(setup, otherRun);
        CountDownLatch release = new CountDownLatch(1);
        litellm.onModel((exchange, request, key) -> {
            try {
                release.await(30, TimeUnit.SECONDS);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            FakeLiteLlm.json(exchange, 200, JSON.createObjectNode().put("type", "message"));
        });
        int before = litellm.requests("/v1/messages").size();
        List<CompletableFuture<HttpResponse<String>>> held = new ArrayList<>();
        for (int i = 0; i < 16; i++) {
            held.add(http.sendAsync(HttpRequest.newBuilder(URI.create("http://localhost:" + port + MESSAGES))
                .header("Authorization", "Bearer " + token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(message("claude-sonnet-4-5")))
                .build(), HttpResponse.BodyHandlers.ofString()));
        }
        try {
            await().atMost(ModelGateways.WAIT).until(() -> litellm.requests("/v1/messages").size() == before + 16);
            // The run's seventeenth request at once.
            HttpResponse<String> seventeenth = post(MESSAGES, token, message("claude-sonnet-4-5"));
            assertThat(seventeenth.statusCode()).isEqualTo(429);
            assertThat(seventeenth.headers().firstValue("retry-after")).contains("1");
            assertThat(json(seventeenth).get("error").get("type").asString()).isEqualTo("rate_limit_error");
            // Another run on the same host: a host that has not connected has one slot, so sixteen requests.
            assertThat(post(MESSAGES, other, message("claude-sonnet-4-5")).statusCode()).isEqualTo(429);
        }
        finally {
            release.countDown();
        }
        for (CompletableFuture<HttpResponse<String>> request : held) {
            assertThat(request.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        assertThat(post(MESSAGES, other, message("claude-sonnet-4-5")).statusCode()).isEqualTo(200);
        assertThat(post(MESSAGES, token, message("claude-sonnet-4-5")).statusCode()).isEqualTo(200);
    }

    @Test
    void aSpentBudgetOrARefusedKeyChangesTheKeysStatusAndNoKeyGoesBack() {
        Setup setup = setUp();
        String token = running(setup, UUID.randomUUID());
        String body = message("claude-sonnet-4-5");

        // LiteLLM refuses the spent budget: the key is BUDGET_SPENT, once audited.
        setup.key().spend = setup.key().maxBudget;
        HttpResponse<String> spent = post(MESSAGES, token, body);
        assertThat(spent.statusCode()).isEqualTo(429);
        assertThat(json(spent).get("error").get("type").asString()).isEqualTo("budget_exceeded");
        ModelGateways.awaitStatus(setup.owner(), setup.company(), setup.payer(), "BUDGET_SPENT");
        assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(429);
        assertThat(auditEvents(setup.owner(), setup.company(), "model_key.budget_spent")).hasSize(1);
        setup.key().spend = 0;
        assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(200);

        // A key LiteLLM lost: its 401 echoes the key abbreviated and its hash, which the relay does not pass on; the
        // key is ERROR, audited, and the sync makes a new one.
        String lost = setup.key().key;
        litellm.forget(setup.key().token);
        HttpResponse<String> refused = post(MESSAGES, token, body);
        assertThat(refused.statusCode()).isEqualTo(401);
        String abbreviated = "sk-..." + lost.substring(lost.length() - 4);
        assertThat(refused.body()).contains("Received API Key = sk-***")
            .doesNotContain(abbreviated)
            .doesNotContain(FakeLiteLlm.hash(lost));
        List<JsonNode> refusals = auditEvents(setup.owner(), setup.company(), "model_key.refused");
        assertThat(refusals).hasSize(1);
        assertThat(refusals.getFirst().get("details").get("status").asInt()).isEqualTo(401);
        assertThat(refusals.getFirst().toString()).doesNotContain(abbreviated).doesNotContain(FakeLiteLlm.hash(lost));
        await().atMost(ModelGateways.WAIT)
            .until(() -> litellm.keyOfAlias(ModelGateways.alias(setup.company(), setup.payer()))
                .filter(key -> !key.key.equals(lost))
                .isPresent());
        ModelGateways.awaitStatus(setup.owner(), setup.company(), setup.payer(), "OK");
        assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(200);

        // A suspended principal's key is BLOCKED: 403 before LiteLLM.
        assertThat(setup.owner().post(setup.company().path("/service-identities/" + setup.payer() + "/suspend"), null)
            .status()).isEqualTo(200);
        ModelGateways.awaitStatus(setup.owner(), setup.company(), setup.payer(), "BLOCKED");
        HttpResponse<String> blocked = post(MESSAGES, token, body);
        assertThat(blocked.statusCode()).isEqualTo(403);
        assertThat(json(blocked).get("error").get("type").asString()).isEqualTo("permission_error");
    }

    /**
     * LiteLLM's 400 for a model it does not serve passes through unchanged, and the model is noted for the run: the
     * runner classes the runtime's error PROTOCOL_ERROR, and the run's end says MODEL_NOT_SERVED instead. Another 400
     * notes nothing.
     */
    @Test
    void aModelLiteLlmDoesNotServeIsNotedForTheRunsEnd() {
        Setup setup = setUp();
        UUID runId = UUID.randomUUID();
        String token = running(setup, runId);
        UUID companyId = setup.company().id();

        litellm.onModel((exchange, request, key) -> FakeLiteLlm.json(exchange, 400, JSON.readTree("""
                {"type":"error","error":{"type":"invalid_request_error","message":"Your credit balance is \
                too low"}}""")));
        assertThat(post(MESSAGES, token, message("claude-sonnet-4-5")).statusCode()).isEqualTo(400);
        assertThat(transactions.inCompany(companyId, () -> relay.modelNotServed(companyId, runId, 1))).isEmpty();

        // A model the key allows that the gateway does not serve.
        litellm.onModel((exchange, request, key) -> FakeLiteLlm.json(exchange, 400, JSON.readTree("""
                {"type":"error","error":{"type":"invalid_request_error","message":"anthropic_messages: Invalid model \
                name passed in model=claude-haiku-4-5. Call `/v1/models` to view available models for your \
                key."}}""")));
        HttpResponse<String> refused = post(MESSAGES, token, message("claude-haiku-4-5"));
        assertThat(refused.statusCode()).isEqualTo(400);
        assertThat(refused.body()).contains("Invalid model name passed in model=claude-haiku-4-5");
        assertThat(transactions.inCompany(companyId, () -> relay.modelNotServed(companyId, runId, 1)))
            .contains("claude-haiku-4-5");
        // A later epoch's execution (a reassignment, which reads the model again) is not told of epoch 1's answer.
        assertThat(transactions.inCompany(companyId, () -> relay.modelNotServed(companyId, runId, 2))).isEmpty();
        assertThat(transactions.inCompany(companyId, () -> relay.modelNotServed(companyId, UUID.randomUUID(), 1)))
            .isEmpty();
    }

    /**
     * A suspended payer is refused by its own status, not only by its key's: the block that suspension asks of LiteLLM
     * may fail, which leaves the key {@code ERROR} and usable at LiteLLM until the reconciliation, while a run already
     * holds its token and the relay its revealed key.
     */
    @Test
    void aSuspendedPayerIsRefusedThoughLiteLlmDidNotBlockItsKey() {
        Setup setup = setUp();
        String token = running(setup, UUID.randomUUID());
        String body = message("claude-sonnet-4-5");
        // The key is revealed, and kept in memory for the token.
        assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(200);
        int messages = litellm.requests("/v1/messages").size();
        int models = litellm.requests("/v1/models").size();

        litellm.failNext("/key/block", 500, 1);
        assertThat(setup.owner().post(setup.company().path("/service-identities/" + setup.payer() + "/suspend"), null)
            .status()).isEqualTo(200);
        ModelGateways.awaitStatus(setup.owner(), setup.company(), setup.payer(), "ERROR");
        assertThat(setup.key().blocked).isFalse();

        HttpResponse<String> refused = post(MESSAGES, token, body);
        assertThat(refused.statusCode()).isEqualTo(403);
        assertThat(json(refused).get("error").get("type").asString()).isEqualTo("permission_error");
        assertThat(json(refused).get("error").get("message").asString()).contains("suspended");
        assertThat(refused.body()).doesNotContain(setup.key().key);
        assertThat(get("/api/runner/v1/model/anthropic/v1/models", token).statusCode()).isEqualTo(403);
        // No request went to LiteLLM with the key.
        assertThat(litellm.requests("/v1/messages")).hasSize(messages);
        assertThat(litellm.requests("/v1/models")).hasSize(models);

        // Resumed, the run's token passes again.
        assertThat(setup.owner().post(setup.company().path("/service-identities/" + setup.payer() + "/resume"), null)
            .status()).isEqualTo(200);
        ModelGateways.awaitStatus(setup.owner(), setup.company(), setup.payer(), "OK");
        assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(200);
    }

    /**
     * The admin key of a company's own LiteLLM changes only in the model settings (the package 2 contract, §3.1;
     * CONVENTIONS §9): the Secrets API neither replaces nor deletes the one they keep ({@code 409 SECRET_MANAGED}),
     * since a pasted key would point Tinfirm's management of LiteLLM at another key without the test that turns the
     * relay on. A new key in the settings turns the relay off at once, as before a first test: runs paid by a budget
     * are refused and the relay serves none. The next test reaches LiteLLM with the new key, never the old one, and
     * turns the relay on again. The audit names the change, never a key. The keys are test fixtures.
     */
    @Test
    void aNewAdminKeyGoesThroughTheModelSettingsAndTurnsTheRelayOffUntilTheGatewayIsTestedAgain() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        String firstKey = "sk-test-first-admin-" + UUID.randomUUID();
        String secondKey = "sk-test-second-admin-" + UUID.randomUUID();
        String pasted = "sk-test-pasted-admin-" + UUID.randomUUID();
        // A secret made by hand under the name is an ordinary one: the settings keep their own, LITELLM_ADMIN_KEY_2.
        UUID handMade = createSecret(admin, company.id(), "LITELLM_ADMIN_KEY", "test-hand-made-value");
        try (FakeLiteLlm own = FakeLiteLlm.start(firstKey)) {
            ApiResponse configured = admin.put(company.path("/model-settings"), Map.of("gateway", "EXTERNAL",
                    "defaultBudgetUsd", 25, "adminUrl", own.url(), "adminKey", firstKey));
            assertThat(configured.status()).as("%s", configured).isEqualTo(200);
            UUID payer = createServiceIdentity(admin, company, unique("bot"));
            assertThat(admin.post(company.path("/model-settings/test"), null).status()).isEqualTo(200);
            FakeLiteLlm.Key payerKey = ModelGateways.awaitKey(own, company, payer);
            ModelGateways.awaitStatus(admin, company, payer, "OK");
            // The reconciliation the test started is over: its sweep lists the team's keys last.
            await().atMost(ModelGateways.WAIT).until(() -> !own.requests("/key/list").isEmpty());
            RunnerHostRef host = registerRunnerHost(admin, company.id(), unique("host"), "COMPANY");
            Setup setup = new Setup(company, owner, payer, payerKey, host.id(), host.token());
            String token = running(setup, UUID.randomUUID());
            String body = message("claude-sonnet-4-5");
            assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(200);
            assertThat(forRun(company, payer)).isInstanceOf(RunModelAccess.Budget.class);

            // The Secrets API neither replaces nor deletes the key the settings keep, and points to them.
            UUID kept = secretId(admin, company, "LITELLM_ADMIN_KEY_2");
            ApiResponse replaced = admin.put(company.path("/secrets/" + kept), Map.of("value", pasted));
            assertThat(replaced.status()).as("%s", replaced).isEqualTo(409);
            assertThat(replaced.problemCode()).isEqualTo("SECRET_MANAGED");
            assertThat(replaced.json().get("detail").asString()).contains("`LITELLM_ADMIN_KEY_2`")
                .contains("model settings");
            assertThat(replaced.body()).doesNotContain(pasted);
            ApiResponse deleted = admin.delete(company.path("/secrets/" + kept));
            assertThat(deleted.status()).as("%s", deleted).isEqualTo(409);
            assertThat(deleted.problemCode()).isEqualTo("SECRET_MANAGED");
            assertThat(owner.put(company.path("/secrets/" + kept), Map.of("value", pasted)).status()).isEqualTo(409);
            // Nothing changed: the same key, the relay on, and no secret.updated but the hand-made one's.
            assertThat(admin.put(company.path("/secrets/" + handMade), Map.of("value", "test-hand-made-rotated"))
                .status()).isEqualTo(200);
            assertThat(secretVersion(admin, company, "LITELLM_ADMIN_KEY_2")).isEqualTo(1);
            assertThat(admin.get(company.path("/model-settings")).json().get("relayEnabled").asBoolean()).isTrue();
            assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(200);
            assertThat(auditEvents(owner, company, "secret.updated"))
                .extracting(event -> event.get("targetId").asString())
                .containsExactly(handMade.toString());

            // The operator rotates the key in LiteLLM, which refuses the old one from now on, and an admin enters the
            // new one in the model settings: the relay is off at once, as before a first test.
            own.rotateMasterKey(secondKey);
            int before = own.requests().size();
            ApiResponse rekeyed = admin.put(company.path("/model-settings"), Map.of("gateway", "EXTERNAL",
                    "defaultBudgetUsd", 25, "adminKey", secondKey));
            assertThat(rekeyed.status()).as("%s", rekeyed).isEqualTo(200);
            assertThat(rekeyed.json().get("relayEnabled").asBoolean()).isFalse();
            assertThat(rekeyed.body()).doesNotContain(secondKey);
            // The settings show a gateway changed since its last test: off, tested before, no failure.
            JsonNode untested = admin.get(company.path("/model-settings")).json();
            assertThat(untested.get("relayEnabled").asBoolean()).isFalse();
            assertThat(untested.has("testedAt")).as("%s", untested).isTrue();
            assertThat(untested.has("lastError")).as("%s", untested).isFalse();
            assertThat(secretVersion(admin, company, "LITELLM_ADMIN_KEY_2")).isEqualTo(2);
            // Runs paid by a budget are refused, the relay serves none, and members list no models.
            assertThat(forRun(company, payer)).isInstanceOfSatisfying(RunModelAccess.Refuse.class, refuse -> assertThat(
                    refuse.reason()).isEqualTo(RunModelAccess.RefuseReason.GATEWAY_NOT_CONFIGURED));
            assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(503);
            assertThat(member.get(company.path("/model-settings/models")).problemCode())
                .isEqualTo("GATEWAY_NOT_CONFIGURED");
            // The principals' keys stay: the gateway is the same.
            assertThat(own.key(payerKey.token)).isPresent();

            // The test reaches LiteLLM with the new key and turns the relay on again; nothing reaches LiteLLM's admin
            // API with the old one, also the reconciliation that the passed test starts.
            ApiResponse retested = admin.post(company.path("/model-settings/test"), null);
            assertThat(retested.status()).as("%s", retested).isEqualTo(200);
            await().atMost(ModelGateways.WAIT).until(() -> own.requests("/key/list").size() > 1);
            List<FakeLiteLlm.Request> since = own.requests();
            List<FakeLiteLlm.Request> adminCalls = since.subList(before, since.size()).stream()
                .filter(request -> !request.path().startsWith("/v1/messages")
                        && !request.path().startsWith("/v1/responses")
                        && !request.path().startsWith("/v1/chat"))
                .toList();
            assertThat(adminCalls).extracting(FakeLiteLlm.Request::path)
                .anyMatch(path -> path.startsWith("/v1/models"))
                .anyMatch(path -> path.startsWith("/key/list"));
            assertThat(adminCalls).allSatisfy(request -> assertThat(request.header("authorization"))
                .as(request.path())
                .isEqualTo("Bearer " + secondKey));
            assertThat(admin.get(company.path("/model-settings")).json().get("relayEnabled").asBoolean()).isTrue();
            ModelGateways.awaitStatus(admin, company, payer, "OK");
            assertThat(forRun(company, payer)).isInstanceOf(RunModelAccess.Budget.class);
            assertThat(post(MESSAGES, token, body).statusCode()).isEqualTo(200);
            assertThat(member.get(company.path("/model-settings/models")).status()).isEqualTo(200);

            // The audit: the new key as a change of the settings that turned the relay off, the secret's new version,
            // the test that turned it on again; never a key.
            List<JsonNode> changes = auditEvents(owner, company, "model_settings.changed");
            JsonNode retest = changes.get(0).get("details");
            assertThat(retest.get("tested").asBoolean()).isTrue();
            assertThat(retest.get("ok").asBoolean()).isTrue();
            assertThat(retest.get("relayEnabled").asBoolean()).isTrue();
            JsonNode rekey = changes.get(1);
            assertThat(rekey.get("actor").get("id").asString()).isEqualTo(company.userId("ADMIN").toString());
            assertThat(rekey.get("details").get("changed")).extracting(JsonNode::asString).containsExactly("adminKey");
            assertThat(rekey.get("details").get("relayEnabled").asBoolean()).isFalse();
            JsonNode updated = auditEvents(owner, company, "secret.updated").getFirst();
            assertThat(updated.get("targetId").asString()).isEqualTo(kept.toString());
            assertThat(updated.get("details").get("name").asString()).isEqualTo("LITELLM_ADMIN_KEY_2");
            assertThat(updated.get("details").get("version").asInt()).isEqualTo(2);
            auditEvents(owner, company).forEach(event -> assertThat(event.toString()).doesNotContain(firstKey)
                .doesNotContain(secondKey)
                .doesNotContain(pasted));

            // Deleting the key the settings keep is still refused.
            assertThat(admin.delete(company.path("/secrets/" + kept)).problemCode()).isEqualTo("SECRET_MANAGED");
        }
    }

    private RunModelAccess forRun(TestCompany company, UUID payer) {
        return transactions.inCompany(company.id(), () -> modelAccess.forRun(new RunModelContext(company.id(), payer,
                ModelAccessChoice.BUDGET, RuntimeKind.CLAUDE_CODE, Set.of())));
    }

    private static UUID secretId(ApiClient actor, TestCompany company, String name) {
        return UUID.fromString(secret(actor, company, name).get("id").asString());
    }

    private static int secretVersion(ApiClient actor, TestCompany company, String name) {
        return secret(actor, company, name).get("version").asInt();
    }

    private static JsonNode secret(ApiClient actor, TestCompany company, String name) {
        for (JsonNode secret : actor.get(company.path("/secrets?limit=200")).json().get("items")) {
            if (secret.get("name").asString().equals(name)) {
                return secret;
            }
        }
        throw new AssertionError("No secret named " + name);
    }
}
