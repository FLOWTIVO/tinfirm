package com.flowtivo.tinfirm.account.internal;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.vault.InstanceKeys;

/**
 * The PKCE code verifier of a sign-in (RFC 7636), derived from its state and never stored: base64url without padding
 * of the HMAC-SHA256 of the state under the instance key {@value #KEY_NAME}, 43 characters of the verifier's alphabet.
 * Only the person's browser holds the state, which comes back on the callback; the database keeps its SHA-256, so
 * nothing there yields a verifier, and the key never leaves the vault ({@link InstanceKeys#hmacSha256}).
 */
@Component
class SignInVerifiers {

    /** The instance key the verifiers are derived with. */
    static final String KEY_NAME = "sign-in-pkce";

    /** Keeps the key's MACs of a verifier apart from any other use. */
    private static final String LABEL = "tinfirm sign-in pkce verifier|";

    private final InstanceKeys keys;

    SignInVerifiers(InstanceKeys keys) {
        this.keys = keys;
    }

    /** The verifier of the sign-in of this state; the same for the same state, on any replica. Never log it. */
    String of(String state) {
        byte[] mac = keys.hmacSha256(KEY_NAME, (LABEL + state).getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(mac);
    }
}
