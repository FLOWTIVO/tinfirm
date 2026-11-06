package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.config.annotation.web.configurers.oauth2.server.authorization.client.metadata.InvalidClientMetadataException;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * How long a client metadata document is cached: by its Cache-Control, at most an hour (ADR-0045); how long a fetch
 * may take: the timeout covers the document's body, so a host that begins an answer and stalls holds the fetch no
 * longer than one that never answers; and how many fetches anyone may cause (the security review of 2026-10-27, L11):
 * eight in flight, a few per client address and minute, a refusal remembered for a minute.
 */
class CimdDocumentFetcherTest {

    private final CimdDocumentFetcher fetcher = new CimdDocumentFetcher(new OAuthProperties(
            new OAuthProperties.Cimd(false, List.of(), Duration.ofSeconds(5), 16384, Duration.ofHours(1), 10, 8, 10,
                    Duration.ofSeconds(60))));

    /** Requests of the stub, by path. */
    private final Map<String, AtomicInteger> hits = new ConcurrentHashMap<>();

    /** Released when the test ends: the stalled host waits for it, so that its thread never outlives the test. */
    private final CountDownLatch done = new CountDownLatch(1);

    private HttpServer server;

    @AfterEach
    void stop() {
        done.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void cacheControlDecidesUpToTheCeiling() {
        assertThat(fetcher.ttl(List.of())).isEqualTo(Duration.ofMinutes(5));
        assertThat(fetcher.ttl(List.of("public, max-age=120"))).isEqualTo(Duration.ofSeconds(120));
        assertThat(fetcher.ttl(List.of("max-age=86400"))).isEqualTo(Duration.ofHours(1));
        assertThat(fetcher.ttl(List.of("no-store"))).isZero();
        assertThat(fetcher.ttl(List.of("no-cache, max-age=600"))).isZero();
        assertThat(fetcher.ttl(List.of("max-age=0"))).isZero();
    }

    @Test
    void aDocumentWhoseBodyStallsIsRefusedAtTheTimeout() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/client", this::stalled);
        server.start();
        CimdDocumentFetcher local = new CimdDocumentFetcher(new OAuthProperties(new OAuthProperties.Cimd(true,
                List.of("localhost"), Duration.ofSeconds(1), 16384, Duration.ofHours(1), 10, 8, 10,
                Duration.ofSeconds(60))));
        URI clientId = URI.create("http://localhost:" + server.getAddress().getPort() + "/client");

        long started = System.nanoTime();
        assertThatThrownBy(() -> local.resolve(clientId))
            .isInstanceOf(InvalidClientMetadataException.class)
            .hasMessageContaining("could not be fetched (HttpTimeoutException)");
        // The timeout ended the fetch, body included: nothing waited on the host's next byte.
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(5));
    }

    @Test
    void atMostEightFetchesAreInFlightAndOneMoreIsRefusedAtOnce() throws Exception {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/client", this::stalled);
        server.start();
        CimdDocumentFetcher local = new CimdDocumentFetcher(new OAuthProperties(new OAuthProperties.Cimd(true,
                List.of("localhost"), Duration.ofSeconds(20), 16384, Duration.ofHours(1), 10, 8, 100,
                Duration.ofSeconds(60))));
        String base = "http://localhost:" + server.getAddress().getPort() + "/client/";

        try (ExecutorService callers = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Future<?>> stalled = new ArrayList<>();
            for (int i = 0; i < 8; i++) {
                URI clientId = URI.create(base + i);
                stalled.add(callers.submit(() -> {
                    try {
                        local.resolve(clientId);
                    }
                    catch (InvalidClientMetadataException ex) {
                        // Ended by the test's end.
                    }
                    return null;
                }));
            }
            // Wait until the stub holds all eight.
            long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (hits.values().stream().mapToInt(AtomicInteger::get).sum() < 8 && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(hits.values().stream().mapToInt(AtomicInteger::get).sum()).isEqualTo(8);

            long started = System.nanoTime();
            assertThatThrownBy(() -> local.resolve(URI.create(base + "ninth")))
                .isInstanceOf(InvalidClientMetadataException.class)
                .hasMessageContaining("too many client metadata documents are being fetched");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
            assertThat(hits).doesNotContainKey("/client/ninth");

            done.countDown();
            for (Future<?> call : stalled) {
                call.get(30, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void oneAddressCausesAtMostItsFetchesAMinuteAndARefusalIsRemembered() throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                hits.computeIfAbsent(exchange.getRequestURI().getPath(), path -> new AtomicInteger()).incrementAndGet();
                exchange.sendResponseHeaders(404, -1);
            }
        });
        server.start();
        CimdDocumentFetcher local = new CimdDocumentFetcher(new OAuthProperties(new OAuthProperties.Cimd(true,
                List.of("localhost"), Duration.ofSeconds(5), 16384, Duration.ofHours(1), 10, 8, 2,
                Duration.ofSeconds(60))), HttpClient.Builder.NO_PROXY, () -> "203.0.113.9");
        String base = "http://localhost:" + server.getAddress().getPort();

        for (String path : List.of("/a", "/b")) {
            assertThatThrownBy(() -> local.resolve(URI.create(base + path)))
                .isInstanceOf(InvalidClientMetadataException.class)
                .hasMessageContaining("answered 404");
        }
        // A third document from the same address is refused without a fetch.
        assertThatThrownBy(() -> local.resolve(URI.create(base + "/c")))
            .isInstanceOf(InvalidClientMetadataException.class)
            .hasMessageContaining("too many client metadata documents were asked for from this address");
        // A refused document is refused again from memory, whoever asks, without a fetch and without a token.
        assertThatThrownBy(() -> local.resolve(URI.create(base + "/a")))
            .isInstanceOf(InvalidClientMetadataException.class)
            .hasMessageContaining("answered 404");
        assertThat(hits).containsOnlyKeys("/a", "/b");
        assertThat(hits.get("/a")).hasValue(1);
    }

    @Test
    void theTestSettingsAreRefusedOutsideTheTestProfile() {
        OAuthProperties production = new OAuthProperties(new OAuthProperties.Cimd(false, List.of(),
                Duration.ofSeconds(5), 16384, Duration.ofHours(1), 10, 8, 10, Duration.ofSeconds(60)));
        OAuthProperties http = new OAuthProperties(new OAuthProperties.Cimd(true, List.of(), Duration.ofSeconds(5),
                16384, Duration.ofHours(1), 10, 8, 10, Duration.ofSeconds(60)));
        OAuthProperties privateHosts = new OAuthProperties(new OAuthProperties.Cimd(false, List.of("localhost"),
                Duration.ofSeconds(5), 16384, Duration.ofHours(1), 10, 8, 10, Duration.ofSeconds(60)));
        MockEnvironment operator = new MockEnvironment();
        MockEnvironment tests = new MockEnvironment();
        tests.setActiveProfiles("test");

        assertThat(CimdDocumentFetcher.refuseTestSettingsOutsideTests(production, operator)).isSameAs(production);
        for (OAuthProperties relaxed : List.of(http, privateHosts)) {
            assertThatThrownBy(() -> CimdDocumentFetcher.refuseTestSettingsOutsideTests(relaxed, operator))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("for the tests only");
            assertThat(CimdDocumentFetcher.refuseTestSettingsOutsideTests(relaxed, tests)).isSameAs(relaxed);
        }
    }

    /** Answers the headers and the start of a JSON document, then nothing until the test ends. */
    private void stalled(HttpExchange exchange) throws IOException {
        hits.computeIfAbsent(exchange.getRequestURI().getPath(), path -> new AtomicInteger()).incrementAndGet();
        try (exchange) {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            out.write("{\"client_id\": \"".getBytes(StandardCharsets.UTF_8));
            out.flush();
            done.await(30, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
        catch (IOException ex) {
            // The client closed the connection: the end of this answer.
        }
    }
}
