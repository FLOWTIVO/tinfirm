package com.flowtivo.tinfirm.identity.internal;

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
 * Personal access tokens (ADR-0045): {@code tfp_} + base64url of 32 random bytes (43 characters). The server keeps
 * only {@code hex(sha256(token))}; the token is shown once, when it is created. The prefix lets secret scanners find
 * a leaked token, and lets the bearer chain tell a personal token from an OAuth access token (a JWT) by its format.
 */
final class ApiTokens {

    static final String PREFIX = "tfp_";

    private static final Pattern FORMAT = Pattern.compile("^tfp_[A-Za-z0-9_-]{43}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private ApiTokens() {
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

    /** Whether the value claims to be a personal access token: it starts with {@code tfp_}. */
    static boolean hasPrefix(@Nullable String token) {
        return token != null && token.startsWith(PREFIX);
    }

    /** Whether the value has the exact token format. */
    static boolean isWellFormed(@Nullable String token) {
        return token != null && FORMAT.matcher(token).matches();
    }

    /**
     * The credentials of an {@code Authorization: Bearer} header (any format), or empty when the header is missing
     * or not of the Bearer scheme.
     */
    static Optional<String> bearerOf(@Nullable String header) {
        if (header == null || header.length() < 7 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        return Optional.of(header.substring(7).trim());
    }
}
