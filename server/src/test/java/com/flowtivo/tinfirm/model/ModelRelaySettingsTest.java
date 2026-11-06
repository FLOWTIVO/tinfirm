package com.flowtivo.tinfirm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.json.JsonMapper;

/**
 * The model relay under an operator's settings other than the defaults, set through their properties in a context of
 * its own ({@link ModelRelayTest} has the defaults):
 * <ul>
 * <li>{@code TINFIRM_MODEL_RELAY_MAX_INFLIGHT_BYTES} of 3 MiB with bodies of at most 1 MiB: a body beyond what the
 * replica may hold while others are relayed is 429 with {@code Retry-After}, declared or streamed, and the bytes come
 * back when those requests end;</li>
 * <li>{@code TINFIRM_MODEL_RELAY_BODY_TIMEOUT} of 3 s (M3 of the security review of 2026-10-27): bodies that declare
 * their length and arrive slowly hold only what they sent, so that they never take the replica's budget from others,
 * and a body still arriving after the deadline is 408.</li>
 * </ul>
 */
@TestPropertySource(properties = { "tinfirm.model.relay-max-request-bytes=1048576", "tinfirm.model.relay-max-inflight-bytes=3145728",
        "tinfirm.model.relay-body-timeout=PT3S" })
class ModelRelaySettingsTest extends IntegrationTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String MESSAGES = "/api/runner/v1/model/anthropic/v1/messages";

    private static final String BODY = "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,"
            + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    private ModelRelay relay;

    @Autowired
    private SystemTransactions transactions;

    /** A company whose bundled gateway passed its test, and a service identity that pays. */
    private record Setup(TestCompany company, ApiClient owner, UUID payer) {
    }

    @AfterEach
    void resetModelHandler() {
        litellm.resetModelHandler();
    }

    private Setup setUp() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID payer = createServiceIdentity(owner, company, unique("bot"));
        ModelGateways.awaitKey(litellm, company, payer);
        ModelGateways.awaitStatus(owner, company, payer, "OK");
        return new Setup(company, owner, payer);
    }

    @Test
    void bodiesBeyondWhatTheReplicaMayHoldAreRefusedUntilOthersEnd() throws Exception {
        Setup setup = setUp();
        RunnerHostRef host = registerRunnerHost(setup.owner(), setup.company().id(), unique("host"), "COMPANY");
        String token = running(setup, host.id());
        // Just under 1 MiB, which the relay writes anew at about the same size.
        String large = "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\","
                + "\"content\":\"" + "x".repeat(1024 * 1024 - 200) + "\"}]}";
        int before = litellm.requests("/v1/messages").size();
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
        List<CompletableFuture<HttpResponse<String>>> held;
        try {
            // Two large bodies, one after the other, while LiteLLM holds their answers: each took 2 MiB while it was
            // read and keeps 1 MiB.
            CompletableFuture<HttpResponse<String>> first = postAsync(token, host.token(), large);
            await().atMost(Duration.ofSeconds(30))
                .until(() -> litellm.requests("/v1/messages").size() == before + 1);
            held = List.of(first, postAsync(token, host.token(), large));
            await().atMost(Duration.ofSeconds(30))
                .until(() -> litellm.requests("/v1/messages").size() == before + 2);

            // A third does not fit in 3 MiB: with its length declared, refused once it reaches the cap as it is read...
            HttpResponse<String> declared = post(token, host.token(), large);
            assertThat(declared.statusCode()).as(declared.body()).isEqualTo(429);
            assertThat(declared.headers().firstValue("retry-after")).contains("1");
            assertThat(JSON.readTree(declared.body()).get("error").get("type").asString())
                .isEqualTo("rate_limit_error");
            // ...and streamed, refused once it reaches the cap.
            HttpResponse<String> streamed = postStreamed(token, host.token(), large);
            assertThat(streamed.statusCode()).as(streamed.body()).isEqualTo(429);
            assertThat(streamed.headers().firstValue("retry-after")).contains("1");
            // A small body still fits, and reaches LiteLLM.
            held = List.of(held.get(0), held.get(1), postAsync(token, host.token(), BODY));
            await().atMost(Duration.ofSeconds(30))
                .until(() -> litellm.requests("/v1/messages").size() == before + 3);
        }
        finally {
            release.countDown();
        }
        for (CompletableFuture<HttpResponse<String>> request : held) {
            assertThat(request.get(30, TimeUnit.SECONDS).statusCode()).isEqualTo(200);
        }
        // The two gave their bytes back: a large body passes again.
        assertThat(post(token, host.token(), large).statusCode()).isEqualTo(200);
        assertThat(litellm.requests("/v1/messages")).hasSize(before + 4);
    }

    /**
     * Two requests that declare 1 MiB each and send a byte now and then: before M3 each took 2 MiB of the 3 MiB at
     * once, so that the second and every other body was 429 while they lasted. Now they hold what they sent, a large
     * body passes beside them, and each is 408 once its deadline passed.
     */
    @Test
    void slowDeclaredBodiesHoldOnlyWhatTheySentAndEndAt408() throws Exception {
        Setup setup = setUp();
        RunnerHostRef host = registerRunnerHost(setup.owner(), setup.company().id(), unique("host"), "COMPANY");
        String token = running(setup, host.id());
        String large = "{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,\"messages\":[{\"role\":\"user\","
                + "\"content\":\"" + "x".repeat(1024 * 1024 - 200) + "\"}]}";
        List<SlowBody> slow = List.of(new SlowBody(token, host.token()), new SlowBody(token, host.token()));
        try {
            long started = System.nanoTime();
            HttpResponse<String> beside = post(token, host.token(), large);
            assertThat(beside.statusCode()).as(beside.body()).isEqualTo(200);
            for (SlowBody body : slow) {
                assertThat(body.status()).startsWith("HTTP/1.1 408");
            }
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(25));
        }
        finally {
            slow.forEach(SlowBody::close);
        }
    }

    /** A request that declares 1 MiB and sends a space every 200 ms until it is answered. */
    private final class SlowBody implements AutoCloseable {

        private final Socket socket;

        private final Thread trickle;

        SlowBody(String token, String hostToken) throws IOException {
            socket = new Socket("localhost", port); // nosemgrep: java.lang.security.audit.crypto.unencrypted-socket.unencrypted-socket
            socket.setSoTimeout(30_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST " + MESSAGES + " HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer " + token
                    + "\r\n" + ModelRelayTest.HOST_TOKEN + ": " + hostToken + "\r\nContent-Type: application/json"
                    + "\r\nContent-Length: 1048576\r\n\r\n{\"model\":").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            trickle = Thread.ofVirtual().start(() -> {
                try {
                    while (!Thread.currentThread().isInterrupted()) {
                        Thread.sleep(200);
                        out.write(' ');
                        out.flush();
                    }
                }
                catch (IOException | InterruptedException ex) {
                    // Answered and closed, or the test ended.
                }
            });
        }

        /** The answer's status line. */
        String status() throws IOException {
            return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII))
                .readLine();
        }

        @Override
        public void close() {
            trickle.interrupt();
            try {
                socket.close();
            }
            catch (IOException ex) {
                // Closed already.
            }
        }
    }

    /** A new run RUNNING on the host with epoch 1, and its one-run token. */
    private String running(Setup setup, UUID hostId) {
        UUID companyId = setup.company().id();
        UUID runId = UUID.randomUUID();
        TestRuns.running(companyId, runId, 1, hostId, setup.payer());
        ModelRelay.RelayedRun run = new ModelRelay.RelayedRun(companyId, runId, 1, hostId, setup.payer(),
                UUID.randomUUID(), UUID.randomUUID());
        return transactions.inCompany(companyId, () -> relay.open(run, RunnerMessages.ModelAccessFormat.ANTHROPIC))
            .apiKey();
    }

    private HttpResponse<String> post(String token, String hostToken, String body) {
        return send(request(token, hostToken).POST(HttpRequest.BodyPublishers.ofString(body)));
    }

    private CompletableFuture<HttpResponse<String>> postAsync(String token, String hostToken, String body) {
        return http.sendAsync(request(token, hostToken).POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    /** Without a declared length: the relay reads the body as it comes. */
    private HttpResponse<String> postStreamed(String token, String hostToken, String body) {
        return send(request(token, hostToken).POST(
                HttpRequest.BodyPublishers.fromPublisher(HttpRequest.BodyPublishers.ofString(body))));
    }

    private HttpRequest.Builder request(String token, String hostToken) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + MESSAGES))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + token);
        if (hostToken != null) {
            request.header(ModelRelayTest.HOST_TOKEN, hostToken);
        }
        return request;
    }

    private HttpResponse<String> send(HttpRequest.Builder request) {
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
}
