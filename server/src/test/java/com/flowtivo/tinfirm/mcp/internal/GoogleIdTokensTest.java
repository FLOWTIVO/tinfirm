package com.flowtivo.tinfirm.mcp.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.support.FakeGoogle;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.GmailCalls;

/**
 * While no key set was held, or the one held was stale, every request read Google's keys, so that while
 * Google could not be reached any request, whatever its token, made Tinfirm call Google. Every read now waits a minute
 * after the one before, whatever asks for it.
 */
class GoogleIdTokensTest {

    private static final GmailAddOnProperties SETTINGS = new GmailAddOnProperties(FakeGoogle.SERVICE_ACCOUNT,
            FakeGoogle.CLIENT_ID);

    private static final String ENDPOINT = "http://localhost:8080/api/gmail/c1/home";

    /** Google's keys, unreachable, counted. */
    private static final class Unreachable implements GmailCalls {

        final AtomicInteger reads = new AtomicInteger();

        @Override
        public CallResult<String> googleKeys() {
            reads.incrementAndGet();
            return CallResult.failed(CallResult.Kind.UNAVAILABLE, "Google could not be reached");
        }
    }

    /** A clock the test moves. */
    private static final class Moving extends Clock {

        Instant now;

        Moving(Instant now) {
            this.now = now;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @Test
    void googlesKeysAreReadAtMostOnceAMinuteWhateverAsks() {
        Unreachable google = new Unreachable();
        Moving clock = new Moving(Instant.parse("2026-10-21T06:00:00Z"));
        GoogleIdTokens tokens = new GoogleIdTokens(google, new TimeSource(clock));
        String system = FakeGoogle.systemToken(ENDPOINT);

        // No set yet, and Google cannot be reached: ten requests read once.
        for (int request = 0; request < 10; request++) {
            assertThat(tokens.verify(SETTINGS, system, "not-a-token", ENDPOINT).account()).isNull();
        }
        assertThat(google.reads).hasValue(1);

        clock.now = clock.now.plus(GoogleIdTokens.READ_AGAIN).minusSeconds(1);
        tokens.verify(SETTINGS, system, "not-a-token", ENDPOINT);
        assertThat(google.reads).hasValue(1);
        clock.now = clock.now.plusSeconds(1);
        tokens.verify(SETTINGS, system, "not-a-token", ENDPOINT);
        tokens.verify(SETTINGS, system, "not-a-token", ENDPOINT);
        assertThat(google.reads).hasValue(2);
    }
}
