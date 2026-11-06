package com.flowtivo.tinfirm.runner.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Runner host tokens (the threat model's A11): {@code tfr_} + base64url of 32 random bytes (43 characters). The server
 * keeps only {@code hex(sha256(token))}; the token is shown once at registration.
 */
final class RunnerTokens {

    static final String PREFIX = "tfr_";

    private static final Pattern FORMAT = Pattern.compile("^tfr_[A-Za-z0-9_-]{43}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private RunnerTokens() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** The token of an {@code Authorization: Bearer} header, if it has the token format. */
    static Optional<String> fromAuthorization(@Nullable String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        return fromValue(header.substring(7));
    }

    /** A bare token, as the model relay's {@code X-Tinfirm-Runner-Token} carries it, if it has the token format. */
    static Optional<String> fromValue(@Nullable String value) {
        if (value == null) {
            return Optional.empty();
        }
        String token = value.trim();
        return FORMAT.matcher(token).matches() ? Optional.of(token) : Optional.empty();
    }
}
