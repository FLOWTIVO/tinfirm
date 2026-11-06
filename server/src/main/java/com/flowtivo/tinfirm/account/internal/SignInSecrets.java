package com.flowtivo.tinfirm.account.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * The random values of one sign-in (the package 1 contract, §3.8): the {@code state} that binds the callback to the
 * person's session, kept only as its SHA-256, 32 random bytes in base64url without padding, 43 characters; and the
 * {@code S256} challenge of its PKCE code verifier (RFC 7636), which {@link SignInVerifiers} derives from the state.
 */
final class SignInSecrets {

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder BASE64URL = Base64.getUrlEncoder().withoutPadding();

    private SignInSecrets() {
    }

    /** 32 random bytes, base64url: a {@code state}. */
    static String random() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return BASE64URL.encodeToString(bytes);
    }

    /** The {@code S256} code challenge of a verifier: base64url of its SHA-256 (RFC 7636 §4.2). */
    static String challenge(String verifier) {
        return BASE64URL.encodeToString(sha256(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    /** The SHA-256 of the text in hex: how a state and a session id are kept. */
    static String hash(String text) {
        return HexFormat.of().formatHex(sha256(text.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
