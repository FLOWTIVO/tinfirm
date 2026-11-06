package com.flowtivo.tinfirm.company.scim;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * A company's SCIM token (ADR-0087, decision 3): {@code tfs_} and the base64url of 32 random bytes (43 characters), as
 * a personal access token is (ADR-0045) with a prefix of its own, so that secret scanners tell them apart. The server
 * keeps only {@code hex(sha256(token))}; the token is shown once, when it is issued.
 */
public final class ScimTokenFormat {

    public static final String PREFIX = "tfs_";

    private static final Pattern FORMAT = Pattern.compile("^tfs_[A-Za-z0-9_-]{43}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private ScimTokenFormat() {
    }

    public static String generate() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String token) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.US_ASCII));
            return HexFormat.of().formatHex(digest);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    public static boolean isWellFormed(@Nullable String token) {
        return token != null && FORMAT.matcher(token).matches();
    }
}
