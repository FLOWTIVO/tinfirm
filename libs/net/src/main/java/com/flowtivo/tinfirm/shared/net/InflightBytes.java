package com.flowtivo.tinfirm.shared.net;

import java.util.concurrent.atomic.AtomicLong;

/**
 * The bytes of request bodies a process holds in memory at once, at most {@code max}: the server's model relay
 * ({@code tinfirm.model.relay-max-inflight-bytes}, CONVENTIONS §15.4) and the model port beside LiteLLM
 * ({@code MODEL_API_MAX_INFLIGHT_BYTES}, ADR-0069). A request takes its share as it reads its body, twice the body (as
 * received, and as written anew without LiteLLM's fields), keeps the body as written anew while the answer streams
 * (the forwarded request holds it), and gives all of it back when it ends. A share that would go beyond the cap is not
 * taken: the caller answers the request 429, before it reads more of it.
 */
public final class InflightBytes {

    private final AtomicLong held = new AtomicLong();

    private final long max;

    public InflightBytes(long max) {
        this.max = max;
    }

    /** A request's share, none yet; closing it gives back what it holds. Not shared between threads. */
    public Share share() {
        return new Share();
    }

    /** The bytes held now, by every request of this process. */
    public long held() {
        return held.get();
    }

    private boolean take(long bytes) {
        while (true) {
            long current = held.get();
            if (bytes > max - current) {
                return false;
            }
            if (held.compareAndSet(current, current + bytes)) {
                return true;
            }
        }
    }

    /** One request's bytes. */
    public final class Share implements AutoCloseable {

        private long bytes;

        private Share() {
        }

        /** Takes {@code more} bytes; false, and nothing taken, when the process's cap would be passed. */
        public boolean add(long more) {
            if (more <= 0) {
                return true;
            }
            if (!take(more)) {
                return false;
            }
            bytes += more;
            return true;
        }

        /**
         * Gives back {@code less} of the bytes the share holds, at most all of them: memory its holder let go of
         * before the request ends, such as a buffer replaced by a larger one.
         */
        public void release(long less) {
            long given = Math.min(Math.max(0, less), bytes);
            if (given > 0) {
                held.addAndGet(-given);
                bytes -= given;
            }
        }

        /** Gives back what the share holds beyond {@code kept} bytes. */
        public void keep(long kept) {
            if (kept < bytes) {
                held.addAndGet(kept - bytes);
                bytes = Math.max(0, kept);
            }
        }

        @Override
        public void close() {
            held.addAndGet(-bytes);
            bytes = 0;
        }
    }
}
