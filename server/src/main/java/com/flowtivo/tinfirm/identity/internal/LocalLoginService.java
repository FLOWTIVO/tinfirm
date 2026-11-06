package com.flowtivo.tinfirm.identity.internal;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;

import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Local (email + password) login. Every failure gets the same 401 response; the audit log records the reason.
 * The password is verified before the account state is checked, so timing does not reveal inactive accounts.
 * <p>
 * Every attempt, an unknown email's included, runs a full Argon2id (19 MiB): at most {@value #CONCURRENT_HASHES}
 * of them run at once on a replica, and an attempt that finds no slot within {@link #HASH_WAIT} is answered
 * {@code 429 RATE_LIMITED}, so that a flood of attempts from many addresses cannot exhaust the pod's CPU and memory.
 */
@Service
class LocalLoginService {

    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    /** The password checks that run at once on a replica. */
    static final int CONCURRENT_HASHES = 8;

    /** How long an attempt waits for a free slot before it is refused. */
    static final Duration HASH_WAIT = Duration.ofMillis(500);

    private final Semaphore hashes = new Semaphore(CONCURRENT_HASHES, true);

    private final UserRepository users;

    private final PasswordHasher hasher;

    private final LoginThrottle throttle;

    private final LoginAudit loginAudit;

    private final AuthProperties properties;

    private final TimeSource time;

    private final TransactionTemplate transactions;

    LocalLoginService(UserRepository users, PasswordHasher hasher, LoginThrottle throttle, LoginAudit loginAudit,
            AuthProperties properties, TimeSource time, PlatformTransactionManager transactionManager) {
        this.users = users;
        this.hasher = hasher;
        this.throttle = throttle;
        this.loginAudit = loginAudit;
        this.properties = properties;
        this.time = time;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    AuthenticatedUser authenticate(String email, String password, String clientIp) {
        if (!properties.localEnabled()) {
            throw ApiException.unauthorized("Local login is disabled");
        }
        String normalizedEmail = Emails.normalize(email);
        throttle.admit(normalizedEmail, clientIp);

        Optional<UserEntity> found = users.findByEmail(normalizedEmail);
        String failure = withHashSlot(normalizedEmail, () -> {
            if (found.isEmpty()) {
                hasher.matches(password, null);
                return "unknown_user";
            }
            if (!found.get().isLocal()) {
                hasher.matches(password, null);
                return "not_local_user";
            }
            if (!hasher.matches(password, found.get().getPasswordHash())) {
                return "bad_credentials";
            }
            return found.get().isActive() ? null : "inactive";
        });
        if (failure != null) {
            loginAudit.failed(found.map(UserEntity::getId).orElse(null), failure, null);
            throw ApiException.unauthorized(INVALID_CREDENTIALS);
        }
        // The right password: the email's attempt is given back, so a user's own logins never lock them out.
        throttle.refund(normalizedEmail);

        return transactions.execute(status -> {
            UserEntity user = users.findById(found.get().getId())
                .orElseThrow(() -> ApiException.unauthorized(INVALID_CREDENTIALS));
            user.recordLogin(time.now());
            loginAudit.succeeded(user.getId(), "local", null);
            return user.toAuthenticatedUser();
        });
    }

    /**
     * Runs the password check in one of the replica's slots; 429 {@code RATE_LIMITED} when none is free in time, the
     * email's attempt given back, since nothing was checked.
     */
    private @Nullable String withHashSlot(String normalizedEmail, Supplier<@Nullable String> check) {
        boolean acquired;
        try {
            acquired = hashes.tryAcquire(HASH_WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            acquired = false;
        }
        if (!acquired) {
            throttle.refund(normalizedEmail);
            throw ApiException.rateLimited(Duration.ofSeconds(1));
        }
        try {
            return check.get();
        }
        finally {
            hashes.release();
        }
    }
}
