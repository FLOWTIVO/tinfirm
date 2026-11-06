package com.flowtivo.tinfirm.diagnostics.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import com.flowtivo.tinfirm.diagnostics.internal.SentryTransport.Failed;
import com.flowtivo.tinfirm.diagnostics.internal.SentryTransport.RateLimited;
import com.flowtivo.tinfirm.diagnostics.internal.SentryTransport.Sent;

/**
 * ADR-0070: one send to a destination that speaks Sentry's protocol, against a host of the JDK on the loopback: the
 * answer's status decides the result, and the configured timeout bounds the whole answer, its body included.
 */
class SentryTransportTest {

    private static final Instant NOW = Instant.parse("2026-10-27T10:00:00Z");

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    /** Released by a test, or when it ends: the stalled destination ends its body after it. */
    private final CountDownLatch resume = new CountDownLatch(1);

    private final AtomicInteger requests = new AtomicInteger();

    /** What the destination does with the next request; a test sets it. */
    private final AtomicReference<HttpHandler> behaviour = new AtomicReference<>();

    private HttpServer server;

    private Dsn dsn;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/42/envelope/", exchange -> {
            requests.incrementAndGet();
            behaviour.get().handle(exchange);
        });
        server.start();
        dsn = Dsn.parse("http://0123456789abcdef0123456789abcdef@localhost:" + server.getAddress().getPort() + "/42");
    }

    @AfterEach
    void stop() {
        resume.countDown();
        server.stop(0);
    }

    @Test
    void aDestinationThatStallsItsBodyAfterTheHeadersFailsAsATimeoutAtTheDeadline() {
        behaviour.set(this::stalled);
        SentryTransport transport = new SentryTransport(null, TIMEOUT);

        long started = System.nanoTime();
        SentryTransport.Result result = transport.send(dsn, "envelope".getBytes(StandardCharsets.UTF_8), "0.1.0",
                NOW);
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        // The request's own timeout bounds the headers only; the body's deadline ended the call, as a timeout.
        assertThat(result).isEqualTo(new Failed("The destination localhost could not be reached: "
                + "HttpTimeoutException"));
        assertThat(took).as("the send returned at the deadline, not when the destination let go")
            .isLessThan(Duration.ofSeconds(5));
        assertThat(requests).hasValue(1);
    }

    @Test
    void aSuccessIsSentWhateverTheBodyAndAnAnswerLargerThanTheTransportReadsIsStillItsStatus() {
        SentryTransport transport = new SentryTransport(null, TIMEOUT);
        byte[] envelope = "envelope".getBytes(StandardCharsets.UTF_8);

        behaviour.set(exchange -> answer(exchange, 200, "{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
        assertThat(transport.send(dsn, envelope, "0.1.0", NOW)).isEqualTo(new Sent());

        // A body past the transport's limit is cut off and dropped: the status is all it needs.
        byte[] large = new byte[256 * 1024];
        Arrays.fill(large, (byte) 'a');
        behaviour.set(exchange -> answer(exchange, 200, large));
        assertThat(transport.send(dsn, envelope, "0.1.0", NOW)).isEqualTo(new Sent());

        behaviour.set(exchange -> answer(exchange, 503, new byte[0]));
        assertThat(transport.send(dsn, envelope, "0.1.0", NOW))
            .isEqualTo(new Failed("The destination localhost answered 503"));
    }

    @Test
    void aRateLimitWaitsItsRetryAfterAMinuteWithoutOne() {
        SentryTransport transport = new SentryTransport(null, TIMEOUT);
        byte[] envelope = "envelope".getBytes(StandardCharsets.UTF_8);

        behaviour.set(exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "120");
            answer(exchange, 429, new byte[0]);
        });
        assertThat(transport.send(dsn, envelope, "0.1.0", NOW)).isEqualTo(new RateLimited(NOW.plusSeconds(120)));

        behaviour.set(exchange -> answer(exchange, 429, new byte[0]));
        assertThat(transport.send(dsn, envelope, "0.1.0", NOW)).isEqualTo(new RateLimited(NOW.plusSeconds(60)));
    }

    private static void answer(HttpExchange exchange, int status, byte[] body) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status, body.length == 0 ? -1 : body.length);
            exchange.getResponseBody().write(body);
        }
    }

    /** Answers {@code 200} with 16 bytes of a body without a length, then nothing until {@link #resume}. */
    private void stalled(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("sixteen bytes...".getBytes(StandardCharsets.UTF_8));
            out.flush();
            resume.await(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        catch (IOException ex) {
            // The client closed the connection at its deadline: the end of this answer.
        }
    }
}
