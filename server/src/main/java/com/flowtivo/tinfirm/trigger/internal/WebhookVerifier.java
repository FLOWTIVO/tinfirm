package com.flowtivo.tinfirm.trigger.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.WebhookSystems;

/**
 * A webhook delivery's proof (the package 5 contract, §5.12, C1, row 14, open point 15), checked on the raw body before
 * anything is parsed, counted or stored:
 * <ul>
 * <li>{@code STANDARD} (Standard Webhooks): {@code webhook-id} (1 to 200 printable characters),
 * {@code webhook-timestamp} (Unix seconds, within 300 seconds of the server's clock either way) and
 * {@code webhook-signature} (space-separated {@code v1,{base64}}); one listed signature equals, in constant time, the
 * base64 HMAC-SHA256 of {@code {id}.{timestamp}.{raw body}} keyed with the decoded secret (the bytes of the base64
 * after {@code whsec_}), or for 24 hours after a rotation with the previous one;</li>
 * <li>{@code SECRET_HEADER}: the system's header equals the secret, or the previous one, in constant time.</li>
 * </ul>
 */
final class WebhookVerifier {

    /** Row 14's window, and the libraries' own tolerance. */
    static final Duration WINDOW = Duration.ofSeconds(300);

    private static final Pattern ID = WebhookMapping.DELIVERY_ID;

    private static final Pattern TIMESTAMP = Pattern.compile("^[0-9]{1,12}$");

    private WebhookVerifier() {
    }

    /** Why a delivery was refused, as {@code webhook.refused} counts it. */
    enum Refusal {
        SIGNATURE, WINDOW
    }

    /** What a verified {@code STANDARD} delivery said of itself. */
    record Signed(String id, Instant at) {
    }

    /** The outcome of a check: signed, or refused for a reason. */
    sealed interface Outcome {

        record Verified(@Nullable Signed signed) implements Outcome {
        }

        record Refused(Refusal refusal) implements Outcome {
        }
    }

    /** A {@code STANDARD} delivery's headers against the secrets. */
    static Outcome standard(WebhookSystems.Secrets secrets, @Nullable String id, @Nullable String timestamp,
            @Nullable String signature, byte[] body, Instant now) {
        if (id == null || !ID.matcher(id).matches() || timestamp == null || !TIMESTAMP.matcher(timestamp).matches()
                || signature == null || signature.isBlank()) {
            return new Outcome.Refused(Refusal.SIGNATURE);
        }
        Instant at = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (Duration.between(at, now).abs().compareTo(WINDOW) > 0) {
            return new Outcome.Refused(Refusal.WINDOW);
        }
        List<String> keys = secrets.previous() != null ? List.of(secrets.current(), secrets.previous())
                : List.of(secrets.current());
        for (String key : keys) {
            byte[] expected = sign(key, id, timestamp, body);
            if (expected == null) {
                continue;
            }
            for (String listed : signature.strip().split(" +")) {
                int comma = listed.indexOf(',');
                if (comma < 0 || !"v1".equals(listed.substring(0, comma))) {
                    continue;
                }
                byte[] given;
                try {
                    given = Base64.getDecoder().decode(listed.substring(comma + 1));
                }
                catch (IllegalArgumentException ex) {
                    continue;
                }
                if (MessageDigest.isEqual(expected, given)) {
                    return new Outcome.Verified(new Signed(id, at));
                }
            }
        }
        return new Outcome.Refused(Refusal.SIGNATURE);
    }

    /** A {@code SECRET_HEADER} delivery's header against the secrets. */
    static Outcome secretHeader(WebhookSystems.Secrets secrets, @Nullable String header) {
        if (header == null || header.isEmpty()) {
            return new Outcome.Refused(Refusal.SIGNATURE);
        }
        byte[] given = header.getBytes(StandardCharsets.UTF_8);
        boolean current = MessageDigest.isEqual(given, secrets.current().getBytes(StandardCharsets.UTF_8));
        boolean previous = secrets.previous() != null
                && MessageDigest.isEqual(given, secrets.previous().getBytes(StandardCharsets.UTF_8));
        return current || previous ? new Outcome.Verified(null) : new Outcome.Refused(Refusal.SIGNATURE);
    }

    /** The HMAC-SHA256 of {@code {id}.{timestamp}.{body}} keyed with the decoded secret; null for a secret not base64. */
    static byte @Nullable [] sign(String secret, String id, String timestamp, byte[] body) {
        String encoded = secret.startsWith("whsec_") ? secret.substring("whsec_".length()) : secret;
        byte[] key;
        try {
            key = Base64.getDecoder().decode(encoded);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update((id + "." + timestamp + ".").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return mac.doFinal();
        }
        catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
