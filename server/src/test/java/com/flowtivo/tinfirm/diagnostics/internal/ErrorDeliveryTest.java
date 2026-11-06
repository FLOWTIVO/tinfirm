package com.flowtivo.tinfirm.diagnostics.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.core.SimpleLock;
import tools.jackson.databind.json.JsonMapper;

import com.flowtivo.tinfirm.diagnostics.internal.CapturedError.Source;
import com.flowtivo.tinfirm.diagnostics.internal.ErrorReportStore.ReportRow;
import com.flowtivo.tinfirm.diagnostics.internal.ErrorReportStore.SettingsRow;
import com.flowtivo.tinfirm.shared.config.BuildInfo;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * ADR-0070: the scheduled delivery on a worker, against a destination of the JDK on the loopback and the store and
 * lock as mocks: one pass at a time on a replica, and a destination that stalls its answer does not keep the replica
 * from its later passes.
 */
class ErrorDeliveryTest {

    private static final Duration TIMEOUT = Duration.ofMillis(500);

    private static final String TIMED_OUT = "The destination localhost could not be reached: HttpTimeoutException";

    /** Released by a test, or when it ends: the stalled destination ends its body after it. */
    private final CountDownLatch resume = new CountDownLatch(1);

    private final AtomicInteger requests = new AtomicInteger();

    /** What the destination does with the next request; a test sets it. */
    private final AtomicReference<HttpHandler> behaviour = new AtomicReference<>();

    private final ErrorReportingSettings reporting = mock(ErrorReportingSettings.class);

    private final ErrorReportStore store = mock(ErrorReportStore.class);

    private final SystemTransactions transactions = mock(SystemTransactions.class);

    private final LockProvider locks = mock(LockProvider.class);

    private final SimpleLock lock = mock(SimpleLock.class);

    private final ReportRow row = new ReportRow(UUID.randomUUID(), "fp", Source.SERVER,
            "java.lang.IllegalStateException", "x", "errorreports.test", "Delivery test", null, "Java 25", List.of(),
            1, 0, Instant.parse("2026-10-27T09:00:00Z"), Instant.parse("2026-10-27T09:30:00Z"), "0.1.0", "0.1.0",
            null, null);

    private HttpServer server;

    private ErrorDelivery delivery;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/api/42/envelope/", exchange -> {
            requests.incrementAndGet();
            behaviour.get().handle(exchange);
        });
        server.start();
        Dsn dsn = Dsn.parse("http://0123456789abcdef0123456789abcdef@localhost:" + server.getAddress().getPort()
                + "/42");

        when(reporting.delivering()).thenReturn(true);
        when(reporting.destination()).thenReturn(dsn);
        when(reporting.proxy()).thenReturn(null);
        when(reporting.label()).thenReturn(null);
        when(transactions.instanceWide(any())).thenAnswer(call -> call.<Supplier<Object>>getArgument(0).get());
        when(locks.lock(any())).thenReturn(Optional.of(lock));
        when(lock.extend(any(), any())).thenReturn(Optional.of(lock));
        when(store.startDelivery(any())).thenReturn(true);
        when(store.settings()).thenReturn(new SettingsRow(UUID.randomUUID(), true, null, null, null, null, null,
                null));
        when(store.undelivered(anyInt())).thenReturn(List.of(row));
        BuildInfo build = mock(BuildInfo.class);
        when(build.version()).thenReturn("0.1.0");
        ErrorReportProperties properties = new ErrorReportProperties(null, dsn.raw(), null, null, 1000, 250,
                Duration.ofDays(90), 200, 20, 50, 100, Duration.ofMinutes(10), TIMEOUT);
        delivery = new ErrorDelivery(reporting, store, transactions, build, new TimeSource(Clock.systemUTC()),
                properties, JsonMapper.builder().build(), locks,
                new TinfirmProperties(URI.create("https://tinfirm.example"), Set.of(TinfirmProperties.WORKER_ROLE)));
    }

    @AfterEach
    void stop() {
        resume.countDown();
        server.stop(0);
    }

    @Test
    void aStalledSendEndsAtTheDeadlineAndALaterAutomaticPassProceeds() {
        behaviour.set(this::stalled);

        delivery.scheduledDelivery();
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(requests).hasValue(1));
        // The send is in flight: a tick meanwhile is skipped, since one pass runs at a time on a replica.
        delivery.scheduledDelivery();

        // The stalled answer fails the send at the deadline, as a timeout, which ends the pass and frees the replica.
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store).finishDelivery(any(), eq(TIMED_OUT),
                isNull()));
        verify(locks, times(1)).lock(any());
        assertThat(requests).as("the skipped tick reached neither the lock nor the destination").hasValue(1);

        // A later tick runs its pass: the destination answers now, and the report is delivered.
        behaviour.set(exchange -> answer(exchange, 200, "{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> {
            delivery.scheduledDelivery();
            verify(locks, atLeast(2)).lock(any());
        });
        await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> verify(store, atLeastOnce())
            .delivered(eq(row.id()), eq(row.occurrences()), any()));
        verify(store, times(1)).finishDelivery(any(), eq(TIMED_OUT), isNull());
        assertThat(requests.get()).isGreaterThanOrEqualTo(2);
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
