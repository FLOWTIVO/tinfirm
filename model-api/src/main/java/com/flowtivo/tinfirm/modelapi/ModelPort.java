package com.flowtivo.tinfirm.modelapi;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Flow;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flowtivo.tinfirm.shared.litellm.LiteLlmRequests;
import com.flowtivo.tinfirm.shared.net.InflightBytes;

import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Passes a model request on to LiteLLM and its answer back (ADR-0069).
 * <ul>
 * <li><b>The route</b>: the request's raw path must be one of {@link Routes}, with its method; anything else is
 * {@code 404} (or {@code 405}) and never reaches LiteLLM. The request goes to LiteLLM at the table's path, without
 * its query.</li>
 * <li><b>The body</b> (a {@code POST}): JSON, at most {@code maxRequestBytes}, one object without a repeated name,
 * written anew without LiteLLM's own fields ({@link LiteLlmRequests}), and refused ({@code 400}, naming the field or
 * the tool's type) when it asks the model's provider to run a tool itself: an MCP server ({@code mcp_servers}, an
 * {@code mcp} tool), code execution, a web search, any tool the runtime does not run (ADR-0108; no setting lets one
 * through); {@code drop_params} passes as it came. The
 * bodies held at once stay within {@code maxInflightBytes} ({@link InflightBytes}), counted as their bytes arrive:
 * LiteLLM checks the caller's key only once a body is read, so a request beyond is {@code 429} with
 * {@code Retry-After} as soon as it would pass the cap, and a slow upload holds only what it sent.</li>
 * <li><b>The headers</b>: only the caller's key ({@code Authorization}, {@code x-api-key} or {@code api-key}, which
 * LiteLLM checks), the formats' version headers, {@code Accept}, {@code User-Agent} and {@code x-litellm-tags} (the
 * spend's tags, the caller's own) go on; of the answer's, its type, the request ids and the rate limits come back.</li>
 * <li><b>The answer</b> streams back part by part, each flushed, so that a server-sent stream reaches the caller as
 * LiteLLM sends it; the next part is asked for only once the previous one was written. LiteLLM's answer may take
 * {@code idleTimeout} to start and as long between two parts: then the request fails ({@code 504} before the answer
 * started, a cut connection after). LiteLLM unreachable is {@code 502}.</li>
 * </ul>
 * A refusal is in OpenAI's error format. Nothing of a request's body or its key is logged.
 */
public class ModelPort extends HttpServlet {

    private static final long serialVersionUID = 1L;

    private static final Logger log = LoggerFactory.getLogger(ModelPort.class);

    /** The caller's headers that go on to LiteLLM, lower case. */
    static final List<String> REQUEST_HEADERS = List.of("authorization", "x-api-key", "api-key", "anthropic-version",
            "anthropic-beta", "openai-beta", "accept", "user-agent", "x-litellm-tags");

    /** LiteLLM's headers that come back, lower case. */
    static final Set<String> RESPONSE_HEADERS = Set.of("content-type", "retry-after", "request-id", "x-request-id");

    static final List<String> RESPONSE_HEADER_PREFIXES = List.of("x-ratelimit-", "anthropic-ratelimit-");

    private static final int MAX_HEADER_VALUE = 8192;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final transient ModelApiProperties properties;

    private final transient JsonMapper bodies;

    private final transient HttpClient http;

    private final transient InflightBytes inflight;

    public ModelPort(ModelApiProperties properties) {
        this.properties = properties;
        this.bodies = LiteLlmRequests.mapper(properties.maxRequestBytes());
        this.inflight = new InflightBytes(properties.maxInflightBytes());
        this.http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(properties.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    @Override
    protected void service(HttpServletRequest request, HttpServletResponse response) throws IOException {
        Routes.Route route = Routes.of(request.getRequestURI());
        if (route == null) {
            refuse(response, 404, "not_found", "The model port serves LiteLLM's model routes only");
            return;
        }
        if (!route.method().equals(request.getMethod())) {
            response.setHeader("Allow", route.method());
            refuse(response, 405, "method_not_allowed", route.path() + " takes " + route.method() + " only");
            return;
        }
        // The share holds the body until the answer ends: the forwarded request keeps it.
        try (InflightBytes.Share share = inflight.share()) {
            byte @Nullable [] body = null;
            if (route.body()) {
                body = body(request, response, share);
                if (body == null) {
                    return;
                }
            }
            forward(route, request, body, response);
        }
    }

    /**
     * The request's body, cleaned of LiteLLM's fields; null when it was refused (the answer is written). The share
     * takes twice the body as it arrives (as received and written anew), and keeps the body written anew.
     */
    private byte @Nullable [] body(HttpServletRequest request, HttpServletResponse response, InflightBytes.Share share)
            throws IOException {
        String type = request.getContentType();
        if (type == null || !type.toLowerCase(Locale.ROOT).startsWith("application/json")) {
            refuse(response, 415, "unsupported_media_type", "The request body must be JSON (application/json)");
            return null;
        }
        long max = properties.maxRequestBytes();
        long length = request.getContentLengthLong();
        if (length > max) {
            refuse(response, 413, "request_too_large", "The request body is larger than " + max + " bytes");
            return null;
        }
        // Taken as the bytes arrive, a declared length too: a slow or stalled upload holds only what it sent.
        byte[] raw;
        try {
            raw = read(request.getInputStream(), max, share);
        }
        catch (Refusal refusal) {
            if (refusal.busy) {
                busy(response);
            }
            else {
                refuse(response, 413, "request_too_large", "The request body is larger than " + max + " bytes");
            }
            return null;
        }
        try {
            LiteLlmRequests.Cleaned cleaned = LiteLlmRequests.clean(bodies, raw, false);
            share.keep(cleaned.bytes().length);
            return cleaned.bytes();
        }
        catch (LiteLlmRequests.Refused ex) {
            refuse(response, 400, "invalid_request_error", ex.getMessage());
            return null;
        }
    }

    /** The body, its share taken as it grows, up to {@code max} bytes. */
    private static byte[] read(InputStream in, long max, InflightBytes.Share share) throws IOException, Refusal {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
            total += read;
            if (total > max) {
                throw new Refusal(false);
            }
            if (!share.add(2L * read)) {
                throw new Refusal(true);
            }
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }

    /** A body refused while it is read: larger than the limit, or beyond the bytes the port may hold. */
    private static final class Refusal extends Exception {

        private static final long serialVersionUID = 1L;

        private final boolean busy;

        Refusal(boolean busy) {
            super(null, null, false, false);
            this.busy = busy;
        }
    }

    /** {@code 429}: the port holds as many request bytes as it may; the caller tries again in a moment. */
    private static void busy(HttpServletResponse response) throws IOException {
        response.setHeader("Retry-After", "1");
        refuse(response, 429, "busy", "The model port holds as many request bodies as it may; try again shortly");
    }

    private void forward(Routes.Route route, HttpServletRequest request, byte @Nullable [] body,
            HttpServletResponse response) throws IOException {
        HttpRequest.Builder upstream = HttpRequest.newBuilder(URI.create(properties.upstreamRoot() + route.path()))
            .timeout(properties.idleTimeout())
            .header("Accept-Encoding", "identity");
        for (String name : REQUEST_HEADERS) {
            for (String value : Collections.list(request.getHeaders(name))) {
                if (headerValue(value)) {
                    upstream.header(name, value);
                }
            }
        }
        if (body != null) {
            upstream.header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofByteArray(body));
        }
        else {
            upstream.GET();
        }
        HttpResponse<Flow.Publisher<List<ByteBuffer>>> answer;
        try {
            answer = http.send(upstream.build(), HttpResponse.BodyHandlers.ofPublisher());
        }
        catch (HttpTimeoutException ex) {
            log.warn("LiteLLM did not answer {} within {}", route.path(), properties.idleTimeout());
            refuse(response, 504, "timeout", "The model gateway did not answer in time");
            return;
        }
        catch (IOException ex) {
            log.warn("LiteLLM could not be reached for {}: {}", route.path(), ex.getClass().getSimpleName());
            refuse(response, 502, "bad_gateway", "The model gateway could not be reached");
            return;
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            refuse(response, 503, "unavailable", "The model port is stopping");
            return;
        }
        stream(route, answer, response);
    }

    /** Writes LiteLLM's answer back as it arrives. */
    private void stream(Routes.Route route, HttpResponse<Flow.Publisher<List<ByteBuffer>>> answer,
            HttpServletResponse response) throws IOException {
        Pump pump = new Pump();
        answer.body().subscribe(pump);
        response.setStatus(answer.statusCode());
        copyHeaders(answer.headers(), response);
        try {
            OutputStream out = response.getOutputStream();
            response.flushBuffer();
            while (true) {
                Object item = pump.next(properties.idleTimeout());
                if (item == Pump.END) {
                    return;
                }
                if (item == null) {
                    throw new IOException("LiteLLM's answer to " + route.path() + " stalled for "
                            + properties.idleTimeout());
                }
                if (item instanceof Throwable failure) {
                    throw new IOException("LiteLLM's answer to " + route.path() + " broke off: "
                            + failure.getClass().getSimpleName());
                }
                @SuppressWarnings("unchecked")
                List<ByteBuffer> buffers = (List<ByteBuffer>) item;
                for (ByteBuffer buffer : buffers) {
                    if (buffer.hasArray()) {
                        out.write(buffer.array(), buffer.arrayOffset() + buffer.position(), buffer.remaining());
                    }
                    else {
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        out.write(bytes);
                    }
                }
                out.flush();
                pump.more();
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException("The model port is stopping", ex);
        }
        finally {
            pump.cancel();
        }
    }

    private static void copyHeaders(HttpHeaders headers, HttpServletResponse response) {
        headers.map().forEach((name, values) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            if (RESPONSE_HEADERS.contains(lower) || RESPONSE_HEADER_PREFIXES.stream().anyMatch(lower::startsWith)) {
                values.forEach(value -> response.addHeader(name, value));
            }
        });
    }

    /** A header value that may go on: printable, not too long. */
    static boolean headerValue(String value) {
        return value.length() <= MAX_HEADER_VALUE
                && value.chars().allMatch(c -> c == '\t' || c >= 0x20 && c < 0x7f || c >= 0x80 && c <= 0xff);
    }

    private static void refuse(HttpServletResponse response, int status, String code, String message)
            throws IOException {
        ObjectNode error = JSON.createObjectNode();
        ObjectNode detail = error.putObject("error");
        detail.put("message", message);
        detail.put("type", status >= 500 ? "api_error" : "invalid_request_error");
        detail.put("code", code);
        byte[] bytes = JSON.writeValueAsString(error).getBytes(StandardCharsets.UTF_8);
        response.setStatus(status);
        response.setContentType("application/json");
        response.setContentLength(bytes.length);
        response.getOutputStream().write(bytes);
    }

    /**
     * Takes LiteLLM's answer one part at a time: the next part is asked for only once the previous one was written, so
     * that nothing piles up in memory, and a wait for a part can time out.
     */
    static final class Pump implements Flow.Subscriber<List<ByteBuffer>> {

        static final Object END = new Object();

        private final BlockingQueue<Object> items = new LinkedBlockingQueue<>();

        private Flow.@Nullable Subscription subscription;

        private boolean cancelled;

        @Override
        public synchronized void onSubscribe(Flow.Subscription given) {
            if (cancelled) {
                given.cancel();
                return;
            }
            subscription = given;
            given.request(1);
        }

        @Override
        public void onNext(List<ByteBuffer> item) {
            items.add(item);
        }

        @Override
        public void onError(Throwable failure) {
            items.add(failure);
        }

        @Override
        public void onComplete() {
            items.add(END);
        }

        /** The next part, {@link #END}, a failure, or null when none came within {@code idle}. */
        @Nullable Object next(Duration idle) throws InterruptedException {
            return items.poll(idle.toNanos(), TimeUnit.NANOSECONDS);
        }

        synchronized void more() {
            if (subscription != null && !cancelled) {
                subscription.request(1);
            }
        }

        synchronized void cancel() {
            cancelled = true;
            if (subscription != null) {
                subscription.cancel();
            }
        }
    }
}
