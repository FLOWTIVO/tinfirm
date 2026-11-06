package com.flowtivo.tinfirm.shared.net;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The bounded body handlers of answers from outside hosts (CONVENTIONS §14.4): a body within the limit is read whole;
 * one declared longer is refused before a byte of it is read; one that grows past the limit, without a declared length,
 * is cut off there and its connection closed, however much more its host would send; one that stalls, with or without a
 * declared length, fails as a timeout at the deadline and its connection is closed, however long the request's own
 * timeout would still wait.
 */
class BoundedBodiesTest {

    private static final int LIMIT = 1024;

    /** The request's own timeout, which bounds the headers only: far longer than the body deadlines below. */
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(20);

    private final HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    /** Released when a test ends: the host that declared a long body and sends none of it waits for it. */
    private final CountDownLatch done = new CountDownLatch(1);

    /** Released by a test, or when it ends: the stalled host goes on writing after it. */
    private final CountDownLatch resume = new CountDownLatch(1);

    /** How many bytes the endless host sent before its connection was closed. */
    private final AtomicLong sent = new AtomicLong();

    /** Whether the stalled host's writes after {@link #resume} failed, because its connection was closed. */
    private final CompletableFuture<Boolean> laterWriteFailed = new CompletableFuture<>();

    private HttpServer server;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/exact", exchange -> answer(exchange, LIMIT, false));
        server.createContext("/exact-chunked", exchange -> answer(exchange, LIMIT, true));
        server.createContext("/empty", exchange -> answer(exchange, 0, false));
        server.createContext("/over", exchange -> answer(exchange, LIMIT + 1, false));
        server.createContext("/over-chunked", exchange -> answer(exchange, LIMIT + 1, true));
        server.createContext("/declared", this::declaredAndSilent);
        server.createContext("/endless", this::endless);
        server.createContext("/stalled", this::stalled);
        server.createContext("/declared-stalled", this::declaredAndStalled);
        server.start();
    }

    @AfterEach
    void stop() {
        done.countDown();
        resume.countDown();
        server.stop(0);
    }

    /** A body of {@code length} bytes, with its length declared or chunked without one. */
    private static void answer(HttpExchange exchange, int length, boolean chunked) throws IOException {
        try (exchange) {
            byte[] body = new byte[length];
            Arrays.fill(body, (byte) 'a');
            exchange.sendResponseHeaders(200, chunked ? 0 : length == 0 ? -1 : length);
            exchange.getResponseBody().write(body);
        }
    }

    /** Declares a body of 64 MiB and sends none of it until the test ends. */
    private void declaredAndSilent(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(200, 64L * 1024 * 1024);
            exchange.getResponseBody().flush();
            done.await(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /** Sends a body without a length, 8 KiB at a time, until its connection is closed or 256 MiB went. */
    private void endless(HttpExchange exchange) throws IOException {
        byte[] chunk = new byte[8 * 1024];
        Arrays.fill(chunk, (byte) 'b');
        try (exchange) {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (int i = 0; i < 32 * 1024; i++) {
                out.write(chunk);
                out.flush();
                sent.addAndGet(chunk.length);
            }
        }
        catch (IOException ex) {
            // The client closed the connection: the end of this answer.
        }
    }

    /**
     * Sends 16 bytes of a body without a length, then nothing until {@link #resume}; then writes on, 8 KiB at a time,
     * until a write fails (the connection was closed) or 64 MiB went, and tells {@link #laterWriteFailed} which.
     */
    private void stalled(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("sixteen bytes...".getBytes(StandardCharsets.UTF_8));
            out.flush();
            resume.await(30, TimeUnit.SECONDS);
            byte[] chunk = new byte[8 * 1024];
            Arrays.fill(chunk, (byte) 'c');
            try {
                for (int i = 0; i < 8 * 1024; i++) {
                    out.write(chunk);
                    out.flush();
                }
                laterWriteFailed.complete(false);
            }
            catch (IOException ex) {
                laterWriteFailed.complete(true);
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        catch (IOException ex) {
            // The client closed the connection: the end of this answer.
        }
    }

    /** Declares a body of 100 bytes, sends 10 of them, then nothing until the test ends. */
    private void declaredAndStalled(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.sendResponseHeaders(200, 100);
            OutputStream out = exchange.getResponseBody();
            out.write("ten bytes.".getBytes(StandardCharsets.UTF_8));
            out.flush();
            done.await(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        catch (IOException ex) {
            // The client closed the connection, or the exchange ended short of its declared length.
        }
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + server.getAddress().getPort() + path);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return get(path, REQUEST_TIMEOUT);
    }

    private HttpResponse<String> get(String path, Duration deadline) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri(path)).timeout(REQUEST_TIMEOUT).build();
        return client.send(request, BoundedBodies.ofString(LIMIT, deadline));
    }

    @Test
    void aBodyWithinTheLimitIsReadWhole() throws Exception {
        HttpResponse<String> exact = get("/exact");
        assertThat(exact.statusCode()).isEqualTo(200);
        assertThat(exact.body()).hasSize(LIMIT).matches("a+");
        assertThat(get("/exact-chunked").body()).hasSize(LIMIT);
        assertThat(get("/empty").body()).isEmpty();
        byte[] bytes = client.send(HttpRequest.newBuilder(uri("/exact")).build(),
                BoundedBodies.ofByteArray(LIMIT, REQUEST_TIMEOUT)).body();
        assertThat(new String(bytes, StandardCharsets.UTF_8)).hasSize(LIMIT);
    }

    @Test
    void aBodyPastTheLimitIsNull() throws Exception {
        HttpResponse<String> over = get("/over");
        assertThat(over.statusCode()).isEqualTo(200);
        assertThat(over.body()).isNull();
        assertThat(get("/over-chunked").body()).isNull();
    }

    @Test
    void aBodyDeclaredLongerThanTheLimitIsRefusedBeforeItIsRead() throws Exception {
        long started = System.nanoTime();
        HttpResponse<String> declared = get("/declared");
        assertThat(declared.body()).isNull();
        // The host sent nothing of its body: had it been waited for, the request would have timed out.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(10));
    }

    @Test
    void anEndlessBodyIsCutOffAtTheLimitAndItsConnectionClosed() throws Exception {
        HttpResponse<String> endless = get("/endless");
        assertThat(endless.body()).isNull();
        // The host's writes fail once the connection is closed: it sent far less than its 256 MiB.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long before;
        do {
            before = sent.get();
            Thread.sleep(200);
        }
        while (sent.get() != before && System.nanoTime() < deadline);
        assertThat(sent.get()).isLessThan(64L * 1024 * 1024);
    }

    @Test
    void aStalledBodyFailsAsATimeoutAtTheDeadlineAndItsConnectionIsClosed() throws Exception {
        long started = System.nanoTime();
        assertThatThrownBy(() -> get("/stalled", Duration.ofSeconds(1)))
            .isInstanceOf(HttpTimeoutException.class)
            .hasMessageContaining("did not arrive within PT1S");
        // The deadline ended the call, long before the request's own timeout would have.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
        // The host's later writes fail: the connection was closed with the subscription.
        resume.countDown();
        assertThat(laterWriteFailed.get(10, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void aDeclaredBodyThatStallsFailsAsATimeoutAtTheDeadline() {
        long started = System.nanoTime();
        assertThatThrownBy(() -> get("/declared-stalled", Duration.ofSeconds(1)))
            .isInstanceOf(HttpTimeoutException.class);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void aBodyThatEndsWithinTheDeadlineIsReadWhole() throws Exception {
        assertThat(get("/exact", Duration.ofSeconds(5)).body()).hasSize(LIMIT);
        assertThat(get("/exact-chunked", Duration.ofSeconds(5)).body()).hasSize(LIMIT);
    }

    @Test
    void aNegativeLimitOrAnEmptyDeadlineIsRefused() {
        assertThatThrownBy(() -> BoundedBodies.ofString(-1, REQUEST_TIMEOUT))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedBodies.ofString(LIMIT, Duration.ZERO))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> BoundedBodies.ofByteArray(LIMIT, Duration.ofSeconds(-1)))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
