package com.flowtivo.tinfirm.shared.net;

import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.jspecify.annotations.Nullable;

/**
 * Body handlers of the JDK's HTTP client that read at most a number of bytes of an answer from a host outside the
 * server, and read them within a deadline (CONVENTIONS §14.4; the threat model's 13.6). {@code
 * HttpResponse.BodyHandlers.ofString()} and {@code ofByteArray()} buffer a body whatever its size, so a misconfigured
 * or hostile host could exhaust the server's memory with one answer. A body declared longer than the limit ({@code
 * Content-Length}) is refused before a byte of it is read, and one that grows past the limit is cut off there: the
 * subscription is cancelled, which closes the connection, and the body is {@code null}. The caller refuses a {@code
 * null} body as its own error (an answer that is too large), within the address guard it holds while it reads.
 * <p>
 * A request's timeout ({@code HttpRequest.Builder.timeout}) bounds the wait for the answer's headers only: a host that
 * answers them and then sends the body slowly, or not at all, would hold the caller's thread, its connection and its
 * guard for as long as it likes. So every handler takes a deadline, the whole budget of the call from the moment the
 * handler is made (a caller makes it right before it sends) to the end of the body. A body that has not ended by then
 * is cut off the same way, and the call fails with an {@link HttpTimeoutException}, as one whose headers never came:
 * the caller's handling of a timeout covers both.
 */
public final class BoundedBodies {

    private BoundedBodies() {
    }

    /**
     * The body as UTF-8 text (JSON's encoding, RFC 8259), or {@code null} when it is longer than {@code maxBytes};
     * {@link HttpTimeoutException} when it has not ended {@code deadline} after this call.
     *
     * @throws IllegalArgumentException when {@code maxBytes} is negative or {@code deadline} is not more than zero
     */
    public static HttpResponse.BodyHandler<@Nullable String> ofString(int maxBytes, Duration deadline) {
        HttpResponse.BodyHandler<byte @Nullable []> bytes = ofByteArray(maxBytes, deadline);
        return info -> HttpResponse.BodySubscribers.mapping(bytes.apply(info),
                body -> body != null ? new String(body, StandardCharsets.UTF_8) : null);
    }

    /**
     * The body's bytes, or {@code null} when it is longer than {@code maxBytes}; {@link HttpTimeoutException} when it
     * has not ended {@code deadline} after this call.
     *
     * @throws IllegalArgumentException when {@code maxBytes} is negative or {@code deadline} is not more than zero
     */
    public static HttpResponse.BodyHandler<byte @Nullable []> ofByteArray(int maxBytes, Duration deadline) {
        if (maxBytes < 0) {
            throw new IllegalArgumentException("A body's limit is at least 0 bytes, not " + maxBytes);
        }
        if (deadline.isNegative() || deadline.isZero()) {
            throw new IllegalArgumentException("A body's deadline is more than zero, not " + deadline);
        }
        long due = System.nanoTime() + deadline.toNanos();
        return info -> new Limited(maxBytes, info.headers().firstValueAsLong("Content-Length").orElse(-1), deadline,
                due);
    }

    /**
     * The one thread that ends the bodies past their deadline, made at the first use; a daemon, so that it never holds
     * the JVM's exit. A body that ends in time takes its timer off the queue.
     */
    private static final class Timers {

        static final ScheduledExecutorService SCHEDULER;

        static {
            ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1,
                    Thread.ofPlatform().daemon().name("bounded-bodies-deadline").factory());
            scheduler.setRemoveOnCancelPolicy(true);
            SCHEDULER = scheduler;
        }

        private Timers() {
        }
    }

    /**
     * Collects the body's buffers up to the limit. The client calls it from one thread at a time (the Reactive
     * Streams' rule 1.3), so its buffers need no lock; the timer runs on the scheduler's thread and touches only the
     * result and the subscription, which are safe to share. The result completes once, whoever completes it first,
     * and later signals change nothing: the result's own completion decides who cancels the subscription.
     */
    private static final class Limited implements HttpResponse.BodySubscriber<byte @Nullable []> {

        private final int maxBytes;

        private final long declared;

        private final Duration deadline;

        /** When the body is due, on {@link System#nanoTime()}'s clock. */
        private final long due;

        private final CompletableFuture<byte @Nullable []> body = new CompletableFuture<>();

        private final List<byte[]> received = new ArrayList<>();

        private int size;

        private Flow.@Nullable Subscription subscription;

        private @Nullable ScheduledFuture<?> timer;

        Limited(int maxBytes, long declared, Duration deadline, long due) {
            this.maxBytes = maxBytes;
            this.declared = declared;
            this.deadline = deadline;
            this.due = due;
        }

        @Override
        public CompletionStage<byte @Nullable []> getBody() {
            return body;
        }

        @Override
        public void onSubscribe(Flow.Subscription given) {
            if (subscription != null) {
                given.cancel();
                return;
            }
            subscription = given;
            if (declared > maxBytes) {
                tooLarge();
                return;
            }
            long remaining = due - System.nanoTime();
            if (remaining <= 0) {
                expired();
                return;
            }
            timer = Timers.SCHEDULER.schedule(this::expired, remaining, TimeUnit.NANOSECONDS);
            given.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> buffers) {
            if (body.isDone()) {
                return;
            }
            for (ByteBuffer buffer : buffers) {
                int length = buffer.remaining();
                if ((long) size + length > maxBytes) {
                    tooLarge();
                    return;
                }
                byte[] chunk = new byte[length];
                buffer.get(chunk);
                received.add(chunk);
                size += length;
            }
        }

        @Override
        public void onError(Throwable error) {
            stopTimer();
            received.clear();
            body.completeExceptionally(error);
        }

        @Override
        public void onComplete() {
            stopTimer();
            if (body.isDone()) {
                return;
            }
            byte[] all = new byte[size];
            int at = 0;
            for (byte[] chunk : received) {
                System.arraycopy(chunk, 0, all, at, chunk.length);
                at += chunk.length;
            }
            received.clear();
            body.complete(all);
        }

        /** Past the limit: the body is null, nothing more is read, and the connection is closed. */
        private void tooLarge() {
            stopTimer();
            received.clear();
            if (body.complete(null)) {
                cancel();
            }
        }

        /**
         * Past the deadline, on the scheduler's thread (or at once, when the deadline had passed before the headers
         * came): the call fails as a timeout, nothing more is read, and the connection is closed. The buffers are left
         * to the client's thread, which drops them with the subscriber.
         */
        private void expired() {
            if (body.completeExceptionally(new HttpTimeoutException("the answer's body did not arrive within "
                    + deadline))) {
                cancel();
            }
        }

        private void cancel() {
            Flow.Subscription current = subscription;
            if (current != null) {
                current.cancel();
            }
        }

        private void stopTimer() {
            ScheduledFuture<?> current = timer;
            if (current != null) {
                current.cancel(false);
            }
        }
    }
}
