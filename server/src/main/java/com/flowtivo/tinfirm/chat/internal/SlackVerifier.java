package com.flowtivo.tinfirm.chat.internal;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.ChatSystemInfo;
import com.flowtivo.tinfirm.system.ChatSystems;

/**
 * Slack's signature of a request (the package 5 contract, §5.2, C1, threat model 11.1): {@code X-Slack-Request-Timestamp}
 * within 300 seconds of the server's clock either way, and {@code X-Slack-Signature} equal, in constant time, to
 * {@code v0=} and the hex HMAC-SHA256 of {@code v0:{timestamp}:{raw body}} under the system's signing secret, checked
 * before the body is parsed. The secret is kept in memory per replica for 10 minutes; a signature that fails is checked
 * once more against the secret revealed again, at most once per system per 10 seconds, so that a secret regenerated
 * through another replica works at once (Slack keeps no overlap), and forged requests cannot make every request reveal
 * it. Revealed as {@code secret.accessed} with the purpose {@code chat-signing:{systemId}}.
 */
@Component
class SlackVerifier {

    private static final Logger log = LoggerFactory.getLogger(SlackVerifier.class);

    static final Duration WINDOW = Duration.ofSeconds(300);

    static final Duration KEPT = Duration.ofMinutes(10);

    static final Duration REVEAL_AGAIN = Duration.ofSeconds(10);

    private record Kept(@Nullable String secret, Instant at) {
    }

    private final Map<UUID, Kept> secrets = new ConcurrentHashMap<>();

    private final Map<UUID, Instant> revealedAgain = new ConcurrentHashMap<>();

    private final ChatSystems chatSystems;

    private final TimeSource time;

    SlackVerifier(ChatSystems chatSystems, TimeSource time) {
        this.chatSystems = chatSystems;
        this.time = time;
    }

    /** Whether the request is the system's own, signed in the window. */
    boolean verify(ChatSystemInfo system, @Nullable String timestamp, @Nullable String signature, byte[] body) {
        if (timestamp == null || signature == null || !timestamp.matches("[0-9]{1,12}")) {
            return false;
        }
        Instant now = time.now();
        Instant signed = Instant.ofEpochSecond(Long.parseLong(timestamp));
        if (Duration.between(signed, now).abs().compareTo(WINDOW) > 0) {
            return false;
        }
        Kept kept = secrets.get(system.id());
        if (kept == null || kept.at().plus(KEPT).isBefore(now)) {
            kept = reveal(system);
        }
        if (kept.secret() != null && matches(kept.secret(), timestamp, signature, body)) {
            return true;
        }
        Instant last = revealedAgain.get(system.id());
        if (last != null && last.plus(REVEAL_AGAIN).isAfter(now)) {
            return false;
        }
        revealedAgain.put(system.id(), now);
        Kept fresh = reveal(system);
        return fresh.secret() != null && !fresh.secret().equals(kept.secret())
                && matches(fresh.secret(), timestamp, signature, body);
    }

    private Kept reveal(ChatSystemInfo system) {
        Optional<String> secret = SystemContext.callInCompany(system.companyId(), () -> chatSystems.revealSecret(
                system.companyId(), system.id(), ChatSystems.ChatSecret.SIGNING_SECRET, "chat-signing:"
                        + system.id()));
        Kept kept = new Kept(secret.orElse(null), time.now());
        secrets.put(system.id(), kept);
        return kept;
    }

    /** A new signing secret was stored on this replica: read it at its next use. */
    void forget(UUID systemId) {
        secrets.remove(systemId);
    }

    static boolean matches(String secret, String timestamp, String signature, byte[] body) {
        byte[] expected = ("v0=" + sign(secret, timestamp, body)).getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected, signature.getBytes(StandardCharsets.UTF_8));
    }

    /** The hex HMAC-SHA256 of {@code v0:{timestamp}:{body}}. */
    static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return HexFormat.of().formatHex(mac.doFinal());
        }
        catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            log.error("HmacSHA256 is not available");
            throw new IllegalStateException(ex);
        }
    }
}
