package com.flowtivo.tinfirm.modelapi;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * LiteLLM's port on localhost, as the model port sees it: it records every request and answers as the test asks
 * (by default {@code 200} with a small JSON body and a few headers, LiteLLM's own among them). TEST FIXTURE ONLY.
 */
final class FakeLiteLlm {

    /** A request as LiteLLM got it. */
    record Received(String method, String uri, Map<String, List<String>> headers, String body) {

        String header(String name) {
            return headers.entrySet()
                .stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .flatMap(entry -> entry.getValue().stream())
                .findFirst()
                .orElse(null);
        }
    }

    /** How LiteLLM answers. */
    interface Answer {

        void write(HttpExchange exchange) throws IOException, InterruptedException;
    }

    static final Answer OK = exchange -> {
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("x-request-id", "req-1");
        exchange.getResponseHeaders().add("x-ratelimit-remaining-requests", "99");
        // LiteLLM's own, which say where it sent the request: never back to the caller.
        exchange.getResponseHeaders().add("x-litellm-model-api-base", "https://api.openai.com/v1");
        byte[] bytes = "{\"ok\":true}".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    };

    private final HttpServer server;

    final List<Received> received = new CopyOnWriteArrayList<>();

    private final AtomicReference<Answer> answer = new AtomicReference<>(OK);

    FakeLiteLlm() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.createContext("/", exchange -> {
            byte[] body = exchange.getRequestBody().readAllBytes();
            received.add(new Received(exchange.getRequestMethod(), exchange.getRequestURI().toString(),
                    Map.copyOf(exchange.getRequestHeaders()), new String(body, StandardCharsets.UTF_8)));
            try {
                answer.get().write(exchange);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            catch (IOException ex) {
                // The model port went away mid-answer.
            }
            finally {
                exchange.close();
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor());
        server.start();
    }

    String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void answer(Answer next) {
        answer.set(next);
    }

    void reset() {
        received.clear();
        answer.set(OK);
    }
}
