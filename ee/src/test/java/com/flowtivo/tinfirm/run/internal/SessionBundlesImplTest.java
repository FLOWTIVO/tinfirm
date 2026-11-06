package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

/**
 * The deadline on an upload's body (the security review of 2026-10-27, L24): a body that is not complete within
 * {@link SessionBundlesImpl#READ_DEADLINE} ends the upload, so a slow host holds one of the upload slots for at most
 * that long.
 */
class SessionBundlesImplTest {

    @Test
    void aBodyThatTakesLongerThanItsDeadlineIsRefused() {
        AtomicLong nanos = new AtomicLong();
        // A byte at a time, each ten seconds after the last.
        InputStream trickle = new InputStream() {

            @Override
            public int read() {
                nanos.addAndGet(Duration.ofSeconds(10).toNanos());
                return 'x';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                buffer[offset] = (byte) read();
                return 1;
            }
        };
        InputStream within = new SessionBundlesImpl.Within(trickle, SessionBundlesImpl.READ_DEADLINE, nanos::get);
        assertThatThrownBy(() -> within.readNBytes(1_000)).isInstanceOf(SessionBundlesImpl.BodyTooSlow.class);
        assertThat(nanos.get()).isLessThanOrEqualTo(Duration.ofSeconds(40).toNanos());
        assertThat(SessionBundlesImpl.READ_DEADLINE).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void theBytesOfAReadThatReturnedPastTheDeadlineAreRefused() {
        AtomicLong nanos = new AtomicLong();
        // One read that blocks for 31 seconds and then returns the whole body at once.
        InputStream late = new InputStream() {

            @Override
            public int read() {
                nanos.addAndGet(Duration.ofSeconds(31).toNanos());
                return 'z';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                buffer[offset] = (byte) read();
                return 1;
            }
        };
        InputStream within = new SessionBundlesImpl.Within(late, Duration.ofSeconds(30), nanos::get);
        assertThatThrownBy(() -> within.readNBytes(1)).isInstanceOf(SessionBundlesImpl.BodyTooSlow.class);
    }

    @Test
    void aBodyWithinItsDeadlineIsRead() throws IOException {
        AtomicLong nanos = new AtomicLong();
        InputStream steady = new InputStream() {

            private int left = 3;

            @Override
            public int read() {
                if (left-- <= 0) {
                    return -1;
                }
                nanos.addAndGet(Duration.ofSeconds(9).toNanos());
                return 'y';
            }

            @Override
            public int read(byte[] buffer, int offset, int length) {
                int value = read();
                if (value < 0) {
                    return -1;
                }
                buffer[offset] = (byte) value;
                return 1;
            }
        };
        InputStream within = new SessionBundlesImpl.Within(steady, Duration.ofSeconds(30), nanos::get);
        assertThat(within.readAllBytes()).containsExactly('y', 'y', 'y');
        assertThat(within.markSupported()).isFalse();
    }
}
