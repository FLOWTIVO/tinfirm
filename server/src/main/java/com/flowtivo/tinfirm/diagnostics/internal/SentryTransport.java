package com.flowtivo.tinfirm.diagnostics.internal;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.Executors;

import org.jspecify.annotations.Nullable;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.flowtivo.tinfirm.shared.net.BoundedBodies;

/**
 * Sends an envelope to the destination's envelope endpoint with {@code X-Sentry-Auth} (ADR-0070, §5): one client, its
 * timeout, no redirects, and the operator's proxy when one is set (the JVM's proxy settings otherwise). The timeout
 * bounds a send whole: the connect, the wait for the answer's headers, and its body, read within
 * {@value #MAX_ANSWER_BYTES} bytes ({@link BoundedBodies}; the status is all a send needs of it). A destination that
 * answers its headers and then nothing, which the request's own timeout does not bound, so fails the send as a timeout
 * instead of holding the delivery's thread, and with it every later pass of its replica. Never inside a database
 * transaction (CONVENTIONS §10).
 */
final class SentryTransport {

    /** How a send ended. */
    sealed interface Result {
    }

    record Sent() implements Result {
    }

    /** The destination asked to wait ({@code 429}); {@code until} from its {@code Retry-After}, a minute without. */
    record RateLimited(Instant until) implements Result {
    }

    /** Any other failure; {@code reason} names no key and holds nothing of the event. */
    record Failed(String reason) implements Result {
    }

    private static final Duration DEFAULT_RETRY = Duration.ofMinutes(1);

    private static final Duration MAX_RETRY = Duration.ofHours(6);

    /** The most of an answer's body that is read: a tracker answers the event's id, a few dozen bytes. */
    static final int MAX_ANSWER_BYTES = 64 * 1024;

    private final HttpClient client;

    private final Duration timeout;

    SentryTransport(@Nullable InetSocketAddress proxy, Duration timeout) {
        HttpClient.Builder builder = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .executor(Executors.newVirtualThreadPerTaskExecutor());
        if (proxy != null) {
            builder.proxy(ProxySelector.of(proxy));
        }
        this.client = builder.build();
        this.timeout = timeout;
    }

    Result send(Dsn dsn, byte[] envelope, String version, Instant now) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("An error report is never delivered inside a database transaction");
        }
        HttpRequest request = HttpRequest.newBuilder(dsn.envelope())
            .timeout(timeout)
            .header("Content-Type", "application/x-sentry-envelope")
            .header("User-Agent", SentryEvents.SDK + "/" + version)
            .header("X-Sentry-Auth", "Sentry sentry_version=7, sentry_client=" + SentryEvents.SDK + "/" + version
                    + ", sentry_key=" + dsn.publicKey())
            .POST(HttpRequest.BodyPublishers.ofByteArray(envelope))
            .build();
        // The handler is made right before the send: its deadline is the whole send's. A body past the limit is
        // null, and a body that has not ended by the deadline an HttpTimeoutException, caught below as any other.
        HttpResponse<byte @Nullable []> response;
        try {
            response = client.send(request, BoundedBodies.ofByteArray(MAX_ANSWER_BYTES, timeout));
        }
        catch (IOException ex) {
            return new Failed("The destination " + dsn.host() + " could not be reached: "
                    + ex.getClass().getSimpleName());
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return new Failed("The delivery was interrupted");
        }
        int status = response.statusCode();
        if (status >= 200 && status < 300) {
            return new Sent();
        }
        if (status == 429) {
            return new RateLimited(now.plus(retryAfter(response.headers().firstValue("Retry-After"))));
        }
        return new Failed("The destination " + dsn.host() + " answered " + status);
    }

    private static Duration retryAfter(Optional<String> header) {
        try {
            long seconds = Long.parseLong(header.orElse("").strip());
            Duration wait = Duration.ofSeconds(Math.max(1, seconds));
            return wait.compareTo(MAX_RETRY) > 0 ? MAX_RETRY : wait;
        }
        catch (NumberFormatException ex) {
            return DEFAULT_RETRY;
        }
    }
}
