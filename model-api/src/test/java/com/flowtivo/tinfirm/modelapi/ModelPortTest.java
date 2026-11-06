package com.flowtivo.tinfirm.modelapi;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The model port against a fake LiteLLM (ADR-0069): its model routes go on with their body cleaned and only the
 * caller's key and the formats' headers; every other route, method or body is refused before LiteLLM; a stream
 * reaches the caller part by part; LiteLLM's errors come back as they were, its own headers never; LiteLLM gone or
 * silent is 502 or 504; bodies beyond what the port may hold at once are refused, counted as their bytes arrive; a
 * tool the model's provider would run is refused, as at the server's relay.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ModelPortTest {

    private static final FakeLiteLlm LITELLM = new FakeLiteLlm();

    private static final String KEY = "sk-TEST-ONLY-virtual-key";

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("model-api.upstream", LITELLM::url);
        registry.add("model-api.idle-timeout", () -> "PT2S");
        registry.add("model-api.max-request-bytes", () -> "4096");
        registry.add("model-api.max-inflight-bytes", () -> "8192");
        registry.add("management.server.port", () -> "0");
    }

    @BeforeEach
    void reset() {
        LITELLM.reset();
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header("Authorization", "Bearer " + KEY)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void aModelRequestGoesOnWithoutLiteLlmsFieldsAndWithOnlyTheCallersHeaders() throws Exception {
        HttpResponse<String> answer = client.send(HttpRequest
            .newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/embeddings?litellm_debug=true"))
            .header("Authorization", "Bearer " + KEY)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("x-litellm-tags", "reports:weekly")
            .header("x-litellm-api-key", "sk-TEST-ONLY-other")
            .header("x-litellm-timeout", "1")
            .header("anthropic-beta", "a")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"text-embedding-3-small\",\"input\":[\"a report\"],"
                    + "\"api_base\":\"https://evil.example\",\"user_config\":{},\"metadata\":{\"m\":1},"
                    + "\"drop_params\":true}"))
            .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.body()).isEqualTo("{\"ok\":true}");
        assertThat(answer.headers().firstValue("x-request-id")).hasValue("req-1");
        assertThat(answer.headers().firstValue("x-ratelimit-remaining-requests")).hasValue("99");
        assertThat(answer.headers().firstValue("x-litellm-model-api-base")).isEmpty();
        FakeLiteLlm.Received sent = LITELLM.received.getFirst();
        assertThat(sent.method()).isEqualTo("POST");
        assertThat(sent.uri()).isEqualTo("/v1/embeddings");
        assertThat(sent.body())
            .isEqualTo("{\"model\":\"text-embedding-3-small\",\"input\":[\"a report\"],\"drop_params\":true}");
        assertThat(sent.header("Authorization")).isEqualTo("Bearer " + KEY);
        assertThat(sent.header("x-litellm-tags")).isEqualTo("reports:weekly");
        assertThat(sent.header("anthropic-beta")).isEqualTo("a");
        assertThat(sent.header("x-litellm-api-key")).isNull();
        assertThat(sent.header("x-litellm-timeout")).isNull();
        assertThat(sent.header("Accept-Encoding")).isEqualTo("identity");
    }

    @Test
    void theModelsTheKeyMayUseAreListed() throws Exception {
        HttpResponse<String> answer = client.send(HttpRequest
            .newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/models"))
            .header("Authorization", "Bearer " + KEY)
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(LITELLM.received.getFirst().method()).isEqualTo("GET");
        assertThat(LITELLM.received.getFirst().uri()).isEqualTo("/v1/models");
    }

    /**
     * LiteLLM's admin API, its pass-through routes, its health and its UI never reach it, however the path is written;
     * Tomcat itself refuses some forms (an encoded slash, a dot segment above the root) before the model port sees them.
     */
    @Test
    void anyOtherRouteIsRefusedBeforeLiteLlm() throws Exception {
        for (String path : new String[] { "/key/generate", "/key/list", "/team/new", "/user/new", "/model/new",
                "/spend/logs", "/global/spend", "/health/liveliness", "/ui", "/", "/v1/chat/completions/",
                "/V1/chat/completions", "/v1/chat/completions;x=1", "/v1//chat/completions", "/v1/./chat/completions",
                "/anthropic/v1/messages", "/openai/v1/chat/completions", "/v1/models/../../key/generate",
                "/v1/files", "/v1/batches", "/v1/images/generations", "/config/update" }) {
            String raw = rawPost(path, "{\"model\":\"m\"}");
            assertThat(raw).as(path).startsWith("HTTP/1.1 4");
        }
        assertThat(LITELLM.received).isEmpty();
    }

    /** A request line as written, which an HTTP client would normalise: plain HTTP to the port on loopback. */
    private String rawPost(String path, String body) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", port)) { // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            socket.setSoTimeout(5000);
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + path + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + KEY
                    + "\r\nContent-Type: application/json\r\nContent-Length: " + bytes.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.flush();
            InputStream in = socket.getInputStream();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void aRouteTakesItsOwnMethodOnly() throws Exception {
        HttpResponse<String> answer = client.send(HttpRequest
            .newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
            .header("Authorization", "Bearer " + KEY)
            .GET()
            .build(), HttpResponse.BodyHandlers.ofString());

        assertThat(answer.statusCode()).isEqualTo(405);
        assertThat(answer.headers().firstValue("Allow")).hasValue("POST");
        assertThat(LITELLM.received).isEmpty();
    }

    /**
     * The relay's filter (M1 of the security review of 2026-10-27; ADR-0108): a tool the provider would run, web search
     * among them, and the MCP connector and the code container are refused before LiteLLM, with what they asked named;
     * the runtime's own tools pass. There is no setting that lets a provider's tool through.
     */
    @Test
    void aToolTheProviderWouldRunIsRefusedBeforeLiteLlm() throws Exception {
        HttpResponse<String> search = post("/v1/messages", "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,"
                + "\"tools\":[{\"type\":\"web_search_20250305\",\"name\":\"web_search\"}],"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertThat(search.statusCode()).isEqualTo(400);
        assertThat(search.body()).contains("invalid_request_error").contains("`web_search_20250305`");
        HttpResponse<String> mcp = post("/v1/responses", "{\"model\":\"gpt-5.4\",\"input\":\"hi\",\"tools\":"
                + "[{\"type\":\"mcp\",\"server_label\":\"x\",\"server_url\":\"https://evil.example/mcp\"}]}");
        assertThat(mcp.statusCode()).isEqualTo(400);
        assertThat(mcp.body()).contains("`mcp`");
        HttpResponse<String> connector = post("/v1/messages", "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,"
                + "\"mcp_servers\":[{\"type\":\"url\",\"url\":\"https://evil.example/mcp\",\"name\":\"x\"}],"
                + "\"tools\":[{\"name\":\"Bash\",\"input_schema\":{\"type\":\"object\"}}],"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertThat(connector.statusCode()).isEqualTo(400);
        assertThat(connector.body()).contains("`mcp_servers`");
        HttpResponse<String> container = post("/v1/messages", "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,"
                + "\"container\":\"c\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertThat(container.statusCode()).isEqualTo(400);
        HttpResponse<String> searchOptions = post("/v1/chat/completions", "{\"model\":\"gpt-4o-search-preview\","
                + "\"web_search_options\":{},\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertThat(searchOptions.statusCode()).isEqualTo(400);
        assertThat(LITELLM.received).isEmpty();

        HttpResponse<String> own = post("/v1/messages", "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,"
                + "\"tools\":[{\"name\":\"Bash\",\"input_schema\":{\"type\":\"object\"}}],"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertThat(own.statusCode()).isEqualTo(200);
        assertThat(LITELLM.received.getFirst().body()).isEqualTo("{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,"
                + "\"tools\":[{\"name\":\"Bash\",\"input_schema\":{\"type\":\"object\"}}],"
                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
    }

    @Test
    void aBodyThatIsNotOneJsonObjectWithinTheLimitIsRefused() throws Exception {
        assertThat(post("/v1/chat/completions", "{\"model\":\"a\",\"model\":\"b\"}").statusCode()).isEqualTo(400);
        assertThat(post("/v1/chat/completions", "not json").statusCode()).isEqualTo(400);
        assertThat(post("/v1/chat/completions", "{\"model\":\"m\",\"x\":\"" + "y".repeat(5000) + "\"}").statusCode())
            .isEqualTo(413);
        HttpResponse<String> form = client.send(HttpRequest
            .newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .POST(HttpRequest.BodyPublishers.ofString("model=m"))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(form.statusCode()).isEqualTo(415);
        assertThat(form.body()).contains("\"error\"").contains("application/json");
        assertThat(LITELLM.received).isEmpty();
    }

    @Test
    void aStreamReachesTheCallerPartByPart() throws Exception {
        CountDownLatch firstRead = new CountDownLatch(1);
        LITELLM.answer(exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("data: {\"n\":1}\n\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            // The rest only once the caller read the first part: a buffered answer would never get here.
            if (!firstRead.await(5, TimeUnit.SECONDS)) {
                return;
            }
            out.write("data: {\"n\":2}\n\ndata: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));
            out.close();
        });

        HttpResponse<InputStream> answer = client.send(HttpRequest
            .newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/chat/completions"))
            .header("Authorization", "Bearer " + KEY)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"m\",\"stream\":true}"))
            .build(), HttpResponse.BodyHandlers.ofInputStream());

        assertThat(answer.statusCode()).isEqualTo(200);
        assertThat(answer.headers().firstValue("Content-Type")).hasValue("text/event-stream");
        try (BufferedReader lines = new BufferedReader(new InputStreamReader(answer.body(), StandardCharsets.UTF_8))) {
            assertThat(lines.readLine()).isEqualTo("data: {\"n\":1}");
            firstRead.countDown();
            assertThat(lines.readLine()).isEmpty();
            assertThat(lines.readLine()).isEqualTo("data: {\"n\":2}");
            assertThat(lines.readLine()).isEmpty();
            assertThat(lines.readLine()).isEqualTo("data: [DONE]");
        }
    }

    @Test
    void liteLlmsRefusalComesBackAsItWas() throws Exception {
        LITELLM.answer(exchange -> {
            byte[] bytes = "{\"error\":{\"message\":\"Authentication Error\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(401, bytes.length);
            exchange.getResponseBody().write(bytes);
        });

        HttpResponse<String> answer = post("/v1/chat/completions", "{\"model\":\"m\"}");

        assertThat(answer.statusCode()).isEqualTo(401);
        assertThat(answer.body()).isEqualTo("{\"error\":{\"message\":\"Authentication Error\"}}");
    }

    @Test
    void bodiesBeyondWhatThePortMayHoldAreRefusedBeforeTheyAreRead() throws Exception {
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LITELLM.answer(exchange -> {
            held.countDown();
            if (release.await(5, TimeUnit.SECONDS)) {
                FakeLiteLlm.OK.write(exchange);
            }
        });
        String body = "{\"model\":\"m\",\"input\":[\"" + "x".repeat(3000) + "\"]}";
        CompletableFuture<HttpResponse<String>> first = client.sendAsync(HttpRequest
            .newBuilder(URI.create("http://127.0.0.1:" + port + "/v1/embeddings"))
            .header("Authorization", "Bearer " + KEY)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build(), HttpResponse.BodyHandlers.ofString());
        assertThat(held.await(5, TimeUnit.SECONDS)).isTrue();

        // The first body is held while its answer is awaited; a second needs twice its size while it is read.
        HttpResponse<String> second = post("/v1/embeddings", body);

        assertThat(second.statusCode()).isEqualTo(429);
        assertThat(second.headers().firstValue("Retry-After")).hasValue("1");
        assertThat(second.body()).contains("\"code\":\"busy\"");
        assertThat(LITELLM.received).hasSize(1);
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        // Once the first answer ended, its bytes are given back.
        LITELLM.answer(FakeLiteLlm.OK);
        assertThat(post("/v1/embeddings", body).statusCode()).isEqualTo(200);
    }

    @Test
    void aSlowUploadHoldsOnlyWhatItSent() throws Exception {
        // A body declared as nearly all the port may hold, of which only a few bytes arrive.
        try (Socket slow = new Socket("127.0.0.1", port)) { // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            OutputStream out = slow.getOutputStream();
            out.write(("POST /v1/embeddings HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + KEY
                    + "\r\nContent-Type: application/json\r\nContent-Length: 4000\r\n\r\n{\"model\":")
                .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            Thread.sleep(300);

            // Another request still fits: the slow one holds its few bytes, not its declared length.
            String body = "{\"model\":\"m\",\"input\":[\"" + "x".repeat(3000) + "\"]}";
            assertThat(post("/v1/embeddings", body).statusCode()).isEqualTo(200);
        }
    }

    @Test
    void liteLlmSilentIs504() throws Exception {
        LITELLM.answer(exchange -> Thread.sleep(Duration.ofSeconds(4)));

        HttpResponse<String> answer = post("/v1/chat/completions", "{\"model\":\"m\"}");

        assertThat(answer.statusCode()).isEqualTo(504);
        assertThat(answer.body()).contains("\"code\":\"timeout\"");
    }
}
