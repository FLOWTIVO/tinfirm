package com.flowtivo.tinfirm.identity.internal;

import org.jspecify.annotations.Nullable;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Argon2id password hashing (OWASP minimum: 19 MiB memory, 2 iterations, parallelism 1, 16-byte salt).
 * Verifying against a missing hash still performs one Argon2 computation, so response times do not reveal whether
 * an account exists.
 */
@Component
class PasswordHasher {

    private static final int SALT_LENGTH = 16;

    private static final int HASH_LENGTH = 32;

    private static final int PARALLELISM = 1;

    private static final int MEMORY_KIB = 19 * 1024;

    private static final int ITERATIONS = 2;

    private final Argon2PasswordEncoder encoder = new Argon2PasswordEncoder(SALT_LENGTH, HASH_LENGTH, PARALLELISM,
            MEMORY_KIB, ITERATIONS);

    private final String timingDummyHash = encoder.encode("timing-equalisation-only");

    String hash(String rawPassword) {
        return encoder.encode(rawPassword);
    }

    boolean matches(String rawPassword, @Nullable String hash) {
        if (hash == null) {
            encoder.matches(rawPassword, timingDummyHash);
            return false;
        }
        return encoder.matches(rawPassword, hash);
    }
}
