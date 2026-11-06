package com.flowtivo.tinfirm.trigger.internal;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanySwitches;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.WebhookLimits;
import com.flowtivo.tinfirm.system.WebhookSystems;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A webhook's deliveries (the package 5 contract, §5.12, C1, C2, C3, open points 11 and 15):
 * {@code POST /api/hooks/{systemId}}, outside {@code /api/companies} and the session chain
 * ({@link WebhookConfiguration}), not in the OpenAPI document (a generated interface would parse a body before its
 * signature is checked). In this order, so that nobody without the secret spends the sender's rate:
 * <ol>
 * <li>{@code application/json} ({@code 415});</li>
 * <li>a client address with more than 60 refused deliveries for the system in the last minute: {@code 429}, before
 * anything else, keyed by the path's system id as given ({@code SOURCE_RATE});</li>
 * <li>the system through {@code webhook_system_by_id} (C2, {@code 404});</li>
 * <li>at most 1 MiB read raw ({@code 413}, {@code SIZE});</li>
 * <li>{@link WebhookVerifier}: {@code 401} without a body, nothing stored, counted for the client address
 * ({@code SIGNATURE}, {@code WINDOW}); the system's secrets are kept 10 minutes per replica and revealed again once on
 * a refusal, so that a rotation through another replica counts at once; a kept previous secret is refused from its own
 * {@code previousUntil} on;</li>
 * <li>the company's stop: {@code 503} with {@code Retry-After: 300}, nothing stored, so that the sender delivers it
 * again after the stop; the system paused by an admin: {@code 200 {"paused": true}}, nothing stored, never fired
 * later;</li>
 * <li>verified deliveries only: at most the system's deliveries a minute ({@code 429} with {@code Retry-After},
 * {@code RATE});</li>
 * <li>the body parsed: an object, at most 32 levels deep and 10 000 values, without the character {@code U+0000}
 * in a name or a string, which {@code jsonb} refuses ({@code 400});</li>
 * <li>{@link WebhookReceiver}: a known delivery id is {@code 200 {"duplicate": true}}; a new one is mapped and fired,
 * {@code 202 {deliveryId, firings}}; {@code 503} while the database fails; a stop or a pause committed after the
 * checks above, which a trigger's gate sees, answers as they do, nothing stored ({@link WebhookFirings.Held}).</li>
 * </ol>
 * Refusals are audited as {@code webhook.refused}, once a minute per system and reason, with their count.
 */
@RestController
class WebhookInbox {

    private static final Logger log = LoggerFactory.getLogger(WebhookInbox.class);

    static final String PATH = "/api/hooks/{systemId}";

    static final long MAX_BODY = 1024 * 1024;

    static final int MAX_VALUES = 10_000;

    /** {@code U+0000}: {@code jsonb} refuses it. */
    private static final char NUL = 0;

    /** A secret revealed here is kept this long (§5.12, §8: {@code secret.accessed} once per replica). */
    static final Duration KEPT = Duration.ofMinutes(10);

    /** The inbound bodies' parser: at most 32 levels deep (open point 11). */
    static final JsonMapper INBOUND = JsonMapper.builder(JsonFactory.builder()
        .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32).build())
        .build()).build();

    private record Kept(WebhookSystems.Secrets secrets, Instant at) {
    }

    private final Map<UUID, Kept> kept = new ConcurrentHashMap<>();

    /** When each system's secrets were last revealed again on a refusal: at most once per 10 seconds, so that a flood
     * of forged deliveries does not reveal them at every one. */
    private final Map<UUID, Instant> revealedAgain = new ConcurrentHashMap<>();

    static final Duration REVEAL_AGAIN_AFTER = Duration.ofSeconds(10);

    private final WebhookSystems webhookSystems;

    private final ObjectProvider<WebhookLimits> limits;

    private final WebhookLimiter limiter;

    private final WebhookReceiver receiver;

    private final CompanySwitches switches;

    private final SystemTransactions transactions;

    private final AuditLog auditLog;

    private final TimeSource time;

    WebhookInbox(WebhookSystems webhookSystems, ObjectProvider<WebhookLimits> limits, WebhookLimiter limiter,
            WebhookReceiver receiver, CompanySwitches switches, SystemTransactions transactions, AuditLog auditLog,
            TimeSource time) {
        this.webhookSystems = webhookSystems;
        this.limits = limits;
        this.limiter = limiter;
        this.receiver = receiver;
        this.switches = switches;
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.time = time;
    }

    @PostMapping(PATH)
    ResponseEntity<?> receive(@PathVariable("systemId") String systemId, HttpServletRequest request)
            throws IOException {
        if (!json(request.getContentType())) {
            return status(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
        }
        // The id first, in its one canonical form: the refusal limiter is keyed by id.toString(), so that an upper-case
        // or short-form spelling of a system's id reads as flooded as its canonical one does (the security review of
        // 2026-10-27, L6). Anything that is not a UUID names no system.
        UUID id = canonicalId(systemId);
        if (id == null) {
            return status(HttpStatus.NOT_FOUND);
        }
        String address = request.getRemoteAddr() != null ? request.getRemoteAddr() : "unknown";
        Instant now = time.now();
        long millis = now.toEpochMilli();
        if (limiter.flooded(id.toString(), address, millis)) {
            // Before the system is looked up: only a known system's refusals count, so the id is one.
            audit(null, id, "SOURCE_RATE", millis);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "60").build();
        }
        UUID companyId = webhookSystems.companyOf(id).orElse(null);
        if (companyId == null) {
            return status(HttpStatus.NOT_FOUND);
        }
        Optional<byte[]> read = body(request);
        if (read.isEmpty()) {
            refused(companyId, id, address, "SIZE", millis);
            return status(HttpStatus.CONTENT_TOO_LARGE);
        }
        byte[] raw = read.get();
        WebhookSystems.WebhookSystem system = transactions.inCompany(companyId,
                () -> webhookSystems.find(companyId, id).orElse(null));
        if (system == null) {
            return status(HttpStatus.NOT_FOUND);
        }
        WebhookVerifier.Outcome outcome = verify(system, request, raw, now);
        if (outcome instanceof WebhookVerifier.Outcome.Refused refused) {
            refused(companyId, id, address, refused.refusal().name(), millis);
            return status(HttpStatus.UNAUTHORIZED);
        }
        WebhookVerifier.Signed signed = ((WebhookVerifier.Outcome.Verified) outcome).signed();
        if (transactions.inCompany(companyId, () -> switches.runsStopped(companyId).isPresent())) {
            return stopped();
        }
        if (system.paused()) {
            return paused();
        }
        int perMinute = system.maxPerMinute() != null ? system.maxPerMinute()
                : transactions.inCompany(companyId, () -> {
                    WebhookLimits found = limits.getIfAvailable();
                    return found != null ? found.defaultPerMinute(companyId) : 60;
                });
        OptionalInt wait = limiter.admit(id, perMinute, millis);
        if (wait.isPresent()) {
            audit(companyId, id, "RATE", millis);
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", Integer.toString(wait.getAsInt()))
                .build();
        }
        JsonNode body = parse(raw);
        if (body == null) {
            return status(HttpStatus.BAD_REQUEST);
        }
        WebhookMapping.Result mapping = WebhookMapping.map(system, body);
        String deliveryId = deliveryId(system, signed, request.getHeader("webhook-id"), mapping, raw);
        try {
            WebhookReceiver.Received received = receiver.receive(system, mapping, deliveryId,
                    signed != null ? signed.at() : null, now, new String(raw, StandardCharsets.UTF_8), raw.length,
                    body);
            return switch (received) {
                case WebhookReceiver.Received.Duplicate duplicate -> ResponseEntity.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("duplicate", true));
                case WebhookReceiver.Received.Accepted accepted -> ResponseEntity.status(HttpStatus.ACCEPTED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("deliveryId", deliveryId, "firings", accepted.firings()));
            };
        }
        catch (WebhookFirings.Held held) {
            // A stop or a pause committed after the checks above: the same answers, nothing stored.
            return held.stopped() ? stopped() : paused();
        }
        catch (DataAccessException ex) {
            log.warn("A delivery to the webhook system {} could not be stored ({})", id, ex.getClass().getSimpleName());
            return status(HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * The system id of the path, or null for one that is not a UUID in its canonical 36-character form (any case):
     * {@link UUID#fromString} also takes short forms such as {@code 1-2-3-4-5}, which would name the same system under
     * other spellings.
     */
    static @Nullable UUID canonicalId(String systemId) {
        if (systemId.length() != 36) {
            return null;
        }
        try {
            return UUID.fromString(systemId);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /** Verifies with the kept secrets, and with the secrets revealed again once when they refuse. */
    private WebhookVerifier.Outcome verify(WebhookSystems.WebhookSystem system, HttpServletRequest request,
            byte[] raw, Instant now) {
        Kept current = kept.get(system.id());
        boolean fresh = current == null || current.at().plus(KEPT).isBefore(now);
        WebhookSystems.Secrets secrets = fresh ? reveal(system) : unexpired(current.secrets(), system, now);
        if (secrets == null) {
            return new WebhookVerifier.Outcome.Refused(WebhookVerifier.Refusal.SIGNATURE);
        }
        WebhookVerifier.Outcome outcome = check(system, request, raw, now, secrets);
        if (outcome instanceof WebhookVerifier.Outcome.Refused refused
                && refused.refusal() == WebhookVerifier.Refusal.SIGNATURE && !fresh && mayRevealAgain(system, now)) {
            WebhookSystems.Secrets again = reveal(system);
            if (again != null) {
                outcome = check(system, request, raw, now, again);
            }
        }
        return outcome;
    }

    /**
     * The kept secrets without the previous one once its own {@code previousUntil} passed (the system as read for this
     * delivery), not only when they are revealed again.
     */
    static WebhookSystems.Secrets unexpired(WebhookSystems.Secrets secrets, WebhookSystems.WebhookSystem system,
            Instant now) {
        Instant until = system.previousUntil();
        if (secrets.previous() == null || until != null && until.isAfter(now)) {
            return secrets;
        }
        return new WebhookSystems.Secrets(secrets.current(), null);
    }

    /** Forgets the system's kept secrets: rotated here, so that the next delivery reads the new one at once. */
    void forget(UUID systemId) {
        kept.remove(systemId);
        revealedAgain.remove(systemId);
    }

    private boolean mayRevealAgain(WebhookSystems.WebhookSystem system, Instant now) {
        Instant last = revealedAgain.get(system.id());
        if (last != null && last.plus(REVEAL_AGAIN_AFTER).isAfter(now)) {
            return false;
        }
        revealedAgain.put(system.id(), now);
        return true;
    }

    private WebhookSystems.@Nullable Secrets reveal(WebhookSystems.WebhookSystem system) {
        WebhookSystems.Secrets secrets = webhookSystems.reveal(system.companyId(), system.id(),
                "webhook-signing:" + system.id()).orElse(null);
        if (secrets != null) {
            kept.put(system.id(), new Kept(secrets, time.now()));
        }
        else {
            kept.remove(system.id());
        }
        return secrets;
    }

    private static WebhookVerifier.Outcome check(WebhookSystems.WebhookSystem system, HttpServletRequest request,
            byte[] raw, Instant now, WebhookSystems.Secrets secrets) {
        return switch (system.scheme()) {
            case STANDARD -> WebhookVerifier.standard(secrets, request.getHeader("webhook-id"),
                    request.getHeader("webhook-timestamp"), request.getHeader("webhook-signature"), raw, now);
            case SECRET_HEADER -> WebhookVerifier.secretHeader(secrets, request.getHeader(system.secretHeader()));
        };
    }

    /** {@code webhook-id}, else the mapped delivery id, else the SHA-256 of the body (§5.12). */
    static String deliveryId(WebhookSystems.WebhookSystem system, WebhookVerifier.@Nullable Signed signed,
            @Nullable String header, WebhookMapping.Result mapping, byte[] raw) {
        if (signed != null) {
            return signed.id();
        }
        if (header != null && WebhookMapping.DELIVERY_ID.matcher(header).matches()) {
            return header;
        }
        if (mapping instanceof WebhookMapping.Result.Read read && read.mapped().deliveryId() != null) {
            return read.mapped().deliveryId();
        }
        return WebhookMapping.sha256(raw);
    }

    private void refused(UUID companyId, UUID systemId, String address, String reason, long millis) {
        limiter.refused(systemId.toString(), address, millis);
        audit(companyId, systemId, reason, millis);
    }

    /**
     * {@code webhook.refused}, once a minute per system and reason, with the count since (§8); the system's company
     * looked up then when not known yet.
     */
    private void audit(@Nullable UUID knownCompanyId, UUID systemId, String reason, long millis) {
        OptionalInt count = limiter.toAudit(systemId, reason, millis);
        if (count.isEmpty()) {
            return;
        }
        try {
            UUID companyId = knownCompanyId != null ? knownCompanyId : webhookSystems.companyOf(systemId).orElse(null);
            if (companyId == null) {
                return;
            }
            transactions.runInCompany(companyId, () -> auditLog.record(AuditRecord.company(companyId,
                    AuditAction.WEBHOOK_REFUSED, CurrentActor.actor())
                .target("system", systemId)
                .detail("reason", reason)
                .detail("count", count.getAsInt())
                .build()));
        }
        catch (RuntimeException ex) {
            log.warn("A refusal of the webhook system {} could not be audited ({})", systemId,
                    ex.getClass().getSimpleName());
        }
    }

    private static boolean json(@Nullable String contentType) {
        if (contentType == null) {
            return false;
        }
        String type = contentType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        return type.equals("application/json");
    }

    /** At most 1 MiB, read raw; empty beyond it. */
    private static Optional<byte[]> body(HttpServletRequest request) throws IOException {
        long length = request.getContentLengthLong();
        if (length > MAX_BODY) {
            return Optional.empty();
        }
        InputStream in = request.getInputStream();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[16 * 1024];
        long total = 0;
        for (int read = in.read(buffer); read != -1; read = in.read(buffer)) {
            total += read;
            if (total > MAX_BODY) {
                return Optional.empty();
            }
            out.write(buffer, 0, read);
        }
        return Optional.of(out.toByteArray());
    }

    /**
     * An object of at most 32 levels and {@value #MAX_VALUES} values, without {@code U+0000} in a field's name or a
     * string (the escape {@code \u0000}, which {@code jsonb} refuses); null otherwise.
     */
    static @Nullable JsonNode parse(byte[] raw) {
        try {
            JsonNode node = INBOUND.readTree(raw);
            if (node == null || !node.isObject() || values(node, MAX_VALUES + 1) > MAX_VALUES || hasNul(node)) {
                return null;
            }
            return node;
        }
        catch (JacksonException ex) {
            return null;
        }
    }

    /** Whether a field's name or a string of the tree holds {@code U+0000}. */
    private static boolean hasNul(JsonNode node) {
        if (node.isString()) {
            return node.asString().indexOf(NUL) >= 0;
        }
        if (node.isObject()) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (field.getKey().indexOf(NUL) >= 0 || hasNul(field.getValue())) {
                    return true;
                }
            }
            return false;
        }
        for (JsonNode child : node) {
            if (hasNul(child)) {
                return true;
            }
        }
        return false;
    }

    /** The values of the tree, counted up to the limit. */
    private static int values(JsonNode node, int limit) {
        int count = 1;
        if (node.isContainer()) {
            for (JsonNode child : node) {
                count += values(child, limit - count);
                if (count >= limit) {
                    return count;
                }
            }
        }
        return count;
    }

    /** The company's stop (package 6, §5.4): nothing stored; the sender delivers it again after. */
    private static ResponseEntity<?> stopped() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "300").build();
    }

    /** The system paused by an admin: nothing stored, never fired later. */
    private static ResponseEntity<?> paused() {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(Map.of("paused", true));
    }

    private static ResponseEntity<?> status(HttpStatus status) {
        return ResponseEntity.status(status).build();
    }
}
