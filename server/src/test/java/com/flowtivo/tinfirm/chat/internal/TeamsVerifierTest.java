package com.flowtivo.tinfirm.chat.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

/**
 * While no key set was held, or the one held was stale, every activity read the Bot Framework's keys, so that while
 * they could not be reached any activity, whatever its token, made Tinfirm call out. Every read now waits a minute
 * after the one before, whatever asks for it, as the Gmail add-on's reads of Google's keys do.
 */
class TeamsVerifierTest {

    private static final ChatSystemInfo SYSTEM = new ChatSystemInfo(new SystemInfo(UUID.randomUUID(),
            UUID.randomUUID(), "Teams", "teams", SystemKind.TEAMS, "https://smba.trafficmanager.net", null, null, false,
            false), null, null, null, "tenant", "app", null, null, null, null, "OK");

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

    /** A token of a key the Bot Framework never published: verifying it asks for the keys. */
    private static String bearer() throws Exception {
        RSAKey key = new RSAKeyGenerator(2048).keyID("test-unpublished-key").generate();
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(TeamsVerifier.ISSUER).build());
        jwt.sign(new RSASSASigner(key));
        return "Bearer " + jwt.serialize();
    }

    @Test
    void theBotFrameworksKeysAreReadAtMostOnceAMinuteWhateverAsks() throws Exception {
        ChatCalls calls = mock(ChatCalls.class);
        when(calls.teamsKeys(any())).thenReturn(CallResult.failed(CallResult.Kind.UNAVAILABLE,
                "The Bot Framework could not be reached"));
        Moving clock = new Moving(Instant.parse("2026-10-21T06:00:00Z"));
        TeamsVerifier verifier = new TeamsVerifier(calls, new TimeSource(clock));
        String token = bearer();

        // No set yet, and the Bot Framework cannot be reached: ten activities read once.
        for (int activity = 0; activity < 10; activity++) {
            assertThat(verifier.problem(SYSTEM, token)).isEqualTo("an unknown key");
        }
        verify(calls, times(1)).teamsKeys(any());

        clock.now = clock.now.plus(TeamsVerifier.READ_AGAIN).minusSeconds(1);
        verifier.problem(SYSTEM, token);
        verify(calls, times(1)).teamsKeys(any());
        clock.now = clock.now.plusSeconds(1);
        verifier.problem(SYSTEM, token);
        verifier.problem(SYSTEM, token);
        verify(calls, times(2)).teamsKeys(any());
    }

    /** A token of the key, which the Bot Framework published or not. */
    private static String bearer(RSAKey key) throws Exception {
        SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(),
                new JWTClaimsSet.Builder().issuer(TeamsVerifier.ISSUER).build());
        jwt.sign(new RSASSASigner(key));
        return "Bearer " + jwt.serialize();
    }

    /**
     * The security review of 2026-10-27, L9: a read of the keys (here for a token of an unknown key) runs outside any
     * monitor, so that an activity whose key is held verifies at once instead of waiting on the Bot Framework.
     */
    @Test
    void aReadOfTheKeysDoesNotHoldBackAnActivityWhoseKeyIsHeld() throws Exception {
        RSAKey published = new RSAKeyGenerator(2048).keyID("test-published-key").generate();
        java.util.Map<String, Object> jwk = new java.util.HashMap<>(published.toPublicJWK().toJSONObject());
        jwk.put("endorsements", java.util.List.of("msteams"));
        String set = com.nimbusds.jose.util.JSONObjectUtils.toJSONString(java.util.Map.of("keys",
                java.util.List.of(jwk)));
        java.util.concurrent.CountDownLatch reading = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger reads = new java.util.concurrent.atomic.AtomicInteger();
        ChatCalls calls = mock(ChatCalls.class);
        when(calls.teamsKeys(any())).thenAnswer(call -> {
            if (reads.incrementAndGet() > 1) {
                reading.countDown();
                release.await(30, java.util.concurrent.TimeUnit.SECONDS);
            }
            return CallResult.ok(set);
        });
        Moving clock = new Moving(Instant.parse("2026-10-27T06:00:00Z"));
        TeamsVerifier verifier = new TeamsVerifier(calls, new TimeSource(clock));
        String held = bearer(published);
        assertThat(verifier.problem(SYSTEM, held)).isNotEqualTo("an unknown key");

        // A minute later a token of an unknown key reads the keys again, and the Bot Framework does not answer.
        clock.now = clock.now.plus(TeamsVerifier.READ_AGAIN);
        String unknown = bearer();
        try (java.util.concurrent.ExecutorService threads = java.util.concurrent.Executors
            .newVirtualThreadPerTaskExecutor()) {
            java.util.concurrent.Future<String> slow = threads.submit(() -> verifier.problem(SYSTEM, unknown));
            assertThat(reading.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();

            java.util.concurrent.Future<String> fast = threads.submit(() -> verifier.problem(SYSTEM, held));
            String problem = fast.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(problem).isNotEqualTo("an unknown key");
            assertThat(slow.isDone()).isFalse();

            release.countDown();
            assertThat(slow.get(10, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo("an unknown key");
        }
        verify(calls, times(2)).teamsKeys(any());
    }
}
