package com.flowtivo.tinfirm.identity.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * L2: at most {@value LocalLoginService#CONCURRENT_HASHES} password checks run at once; one more attempt that finds no
 * slot in time is answered {@code 429 RATE_LIMITED}, before any Argon2id runs for it.
 */
class LocalLoginServiceTest {

    @Test
    void anAttemptBeyondTheConcurrentHashesIsRefusedWithRateLimited() throws Exception {
        UserRepository users = mock(UserRepository.class);
        when(users.findByEmail(anyString())).thenReturn(Optional.empty());
        PasswordHasher hasher = mock(PasswordHasher.class);
        CountDownLatch inside = new CountDownLatch(LocalLoginService.CONCURRENT_HASHES);
        CountDownLatch release = new CountDownLatch(1);
        when(hasher.matches(any(), any())).thenAnswer(invocation -> {
            inside.countDown();
            release.await(10, TimeUnit.SECONDS);
            return false;
        });
        AuthProperties.Limit many = new AuthProperties.Limit(1000, Duration.ofMinutes(1));
        AuthProperties properties = new AuthProperties(true, true, new AuthProperties.LoginThrottleLimits(many, many),
                new AuthProperties.Oidc(Map.of()));
        LocalLoginService service = new LocalLoginService(users, hasher, new LoginThrottle(properties),
                mock(LoginAudit.class), properties, new TimeSource(Clock.systemUTC()),
                mock(PlatformTransactionManager.class));

        ExecutorService pool = Executors.newFixedThreadPool(LocalLoginService.CONCURRENT_HASHES);
        try {
            List<Future<?>> busy = new ArrayList<>();
            for (int i = 0; i < LocalLoginService.CONCURRENT_HASHES; i++) {
                String email = "user" + i + "@example.com";
                busy.add(pool.submit(() -> service.authenticate(email, "guess", "192.0.2.1")));
            }
            assertThat(inside.await(10, TimeUnit.SECONDS)).isTrue();

            assertThatThrownBy(() -> service.authenticate("one-more@example.com", "guess", "192.0.2.2"))
                .isInstanceOfSatisfying(ApiException.class,
                        ex -> assertThat(ex.code()).isEqualTo(ProblemCode.RATE_LIMITED));

            release.countDown();
            for (Future<?> attempt : busy) {
                assertThatThrownBy(attempt::get).hasCauseInstanceOf(ApiException.class);
            }
        }
        finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
