package com.flowtivo.tinfirm.conversation.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The keys of idempotent submissions (package R, §3.5; {@code submission_key}, V026): a REST request's
 * {@code Idempotency-Key}, an MCP tool's {@code requestId}. The conversation module's services claim the key in the
 * transaction that makes the resource, after the caller's authorisation and before anything is locked or made
 * ({@link #claim}); the first request's transaction then writes what it made ({@link #made}). So:
 * <ul>
 * <li>a first use stores the key with its resource, in the same transaction: a concurrent request with the same key
 * waits on the key's primary key, then finds the first one's resource;</li>
 * <li>the same key again, for the same operation and request, is a {@link Claim.Replay}: the service answers the
 * resource as it is now, with the first request's status, and makes, audits and charges nothing new;</li>
 * <li>the same key with another operation or request is {@code 422 IDEMPOTENCY_KEY_REUSED};</li>
 * <li>a request that failed rolled its transaction back, the key with it: the client may retry with the same key.</li>
 * </ul>
 * A key is the caller's own, by their principal in the company: the same key from another member is another key. A
 * caller without a principal (an instance admin who is not a member, who may only retry a conversation) keeps no key,
 * and the request runs as one without. Keys are kept {@code TINFIRM_IDEMPOTENCY_KEY_TTL} (a day): an older one counts
 * as gone, and {@code worker} replicas delete them every {@code tinfirm.conversation.tool-journal.sweep-interval}.
 */
@Component
class SubmissionKeys {

    private static final Logger log = LoggerFactory.getLogger(SubmissionKeys.class);

    /** 1 to 200 visible ASCII characters. */
    static final Pattern KEY = Pattern.compile("[!-~]{1,200}");

    static final String CONVERSATION = "conversation";

    static final String MESSAGE = "message";

    static final String ATTACHMENT = "attachment";

    static final String DECISION = "decision";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final int BATCH = 200;

    /** The submissions that take a key, by their operationId in {@code api/openapi.yaml}. */
    enum Operation {
        startConversation, postConversationMessage, uploadAttachment, retryConversation, decideDecision
    }

    /** What the claim of a key found. */
    sealed interface Claim {

        /** No key: the request runs as ever. */
        record None() implements Claim {
        }

        /** The first use: the key is held by the caller's transaction, which writes what it makes. */
        record First(UUID companyId, UUID principalId, String key) implements Claim {
        }

        /** The same request again: what the first one made. */
        record Replay(String resourceType, UUID resourceId) implements Claim {
        }
    }

    static final Claim NONE = new Claim.None();

    private final JdbcClient jdbc;

    private final SystemTransactions transactions;

    private final TimeSource time;

    private final Duration ttl;

    private final boolean worker;

    SubmissionKeys(JdbcClient jdbc, SystemTransactions transactions, TimeSource time,
            @Value("${tinfirm.conversation.idempotency-key-ttl:PT24H}") Duration ttl, TinfirmProperties tinfirm) {
        if (ttl.isNegative() || ttl.isZero()) {
            throw new IllegalArgumentException("tinfirm.conversation.idempotency-key-ttl must be positive, not " + ttl);
        }
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.time = time;
        this.ttl = ttl;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
    }

    /**
     * Claims the caller's key for the request, in the caller's transaction, before the operation makes anything: a
     * first use inserts it (and waits for a concurrent request with the same key to end), a replay answers what the
     * first one made, a reuse is {@code 422 IDEMPOTENCY_KEY_REUSED}. A malformed key is {@code 400}.
     *
     * @param principalId the caller's principal in the company; null keeps no key
     * @param key         the client's key; null for none
     */
    Claim claim(UUID companyId, @Nullable UUID principalId, @Nullable String key, Operation operation,
            String requestSha256) {
        if (key == null) {
            return NONE;
        }
        if (!KEY.matcher(key).matches()) {
            throw ApiException.validation("An idempotency key has 1 to 200 visible ASCII characters (a UUID is "
                    + "recommended)");
        }
        if (principalId == null) {
            return NONE;
        }
        Instant now = time.now();
        // A key past its lifetime counts as gone, as the sweep deletes it.
        jdbc.sql("DELETE FROM submission_key WHERE company_id = ? AND principal_id = ? AND key = ? AND created_at < ?")
            .params(companyId, principalId, key, ts(now.minus(ttl)))
            .update();
        for (int attempt = 0; attempt < 3; attempt++) {
            int inserted = jdbc.sql("""
                    INSERT INTO submission_key (company_id, principal_id, key, operation, request_sha256, created_at)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON CONFLICT (company_id, principal_id, key) DO NOTHING""")
                .params(companyId, principalId, key, operation.name(), requestSha256, ts(now))
                .update();
            if (inserted == 1) {
                return new Claim.First(companyId, principalId, key);
            }
            // Another request with the key committed first (this one waited for it): its row is visible now.
            List<Map<String, Object>> rows = jdbc.sql("""
                    SELECT operation, request_sha256, resource_type, resource_id FROM submission_key
                     WHERE company_id = ? AND principal_id = ? AND key = ?""")
                .params(companyId, principalId, key)
                .query()
                .listOfRows();
            if (rows.isEmpty()) {
                continue;
            }
            Map<String, Object> row = rows.getFirst();
            if (!operation.name().equals(row.get("operation")) || !requestSha256.equals(row.get("request_sha256"))) {
                throw ApiException.unprocessable(ProblemCode.IDEMPOTENCY_KEY_REUSED, "This idempotency key was used "
                        + "for another request: send a new key for each request, and the same key only when you retry "
                        + "it", Map.of());
            }
            Object resourceId = row.get("resource_id");
            if (row.get("resource_type") == null || resourceId == null) {
                throw new IllegalStateException("A committed submission key without its resource");
            }
            return new Claim.Replay(String.valueOf(row.get("resource_type")), (UUID) resourceId);
        }
        throw new IllegalStateException("The submission key could not be claimed");
    }

    /**
     * What the caller's key made, without claiming it: the resource of a first use of the key for the operation that
     * was answered within the key's lifetime, whatever the request's hash. A client that does work before its request
     * (holds an email's attachments for a start, ADR-0084) asks this first, so that a request sent again does the
     * work once; the claim in the request's own transaction still decides, and this neither waits for a first request
     * that runs (its row is not visible yet) nor refuses anything. Empty for no key, a malformed one, a caller without
     * a principal, a key of another operation, or one no answered request holds.
     */
    Optional<UUID> find(UUID companyId, @Nullable UUID principalId, @Nullable String key, Operation operation) {
        if (key == null || principalId == null || !KEY.matcher(key).matches()) {
            return Optional.empty();
        }
        return jdbc.sql("""
                SELECT resource_id FROM submission_key
                 WHERE company_id = ? AND principal_id = ? AND key = ? AND operation = ?
                   AND resource_id IS NOT NULL AND created_at >= ?""")
            .params(companyId, principalId, key, operation.name(), ts(time.now().minus(ttl)))
            .query(UUID.class)
            .optional();
    }

    /** What the first request made, in its transaction, with its key. Nothing without a first use. */
    void made(Claim claim, String resourceType, UUID resourceId) {
        if (claim instanceof Claim.First first) {
            jdbc.sql("""
                    UPDATE submission_key SET resource_type = ?, resource_id = ?
                     WHERE company_id = ? AND principal_id = ? AND key = ?""")
                .params(resourceType, resourceId, first.companyId(), first.principalId(), first.key())
                .update();
        }
    }

    // ---- the request's hash ---------------------------------------------------------------------------------------

    /** The SHA-256 of the request: its method, its path and its body in canonical form (keys sorted). */
    static String requestSha256(String method, String path, @Nullable Object body) {
        JsonNode node = body instanceof JsonNode json ? json : JSON.valueToTree(body);
        return sha256(method + " " + path + "\n" + (node != null ? ToolJournal.canonical(node).toString() : ""));
    }

    /** An upload's: the file's SHA-256, its name and its note. */
    static String uploadSha256(String path, String fileSha256, String fileName, @Nullable String note) {
        return requestSha256("POST", path, JSON.createObjectNode()
            .put("sha256", fileSha256)
            .put("fileName", fileName)
            .put("note", note));
    }

    private static String sha256(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    // ---- the sweep ------------------------------------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${tinfirm.conversation.tool-journal.sweep-interval:PT1M}",
            initialDelayString = "${tinfirm.conversation.tool-journal.sweep-interval:PT1M}")
    void scheduledSweep() {
        if (worker) {
            try {
                sweep(time.now());
            }
            catch (RuntimeException ex) {
                log.warn("The pass over the submission keys failed", ex);
            }
        }
    }

    /** Deletes the keys created longer than their lifetime before {@code now}, in every company; answers how many. */
    int sweep(Instant now) {
        Instant before = now.minus(ttl);
        List<UUID> companies = transactions.unscoped(() -> jdbc.sql(
                "SELECT company_id FROM submission_key_expired(?, ?)")
            .params(ts(before), BATCH)
            .query(UUID.class)
            .list());
        int deleted = 0;
        for (UUID companyId : companies) {
            try {
                deleted += SystemContext.callInCompany(companyId, () -> transactions.inCompany(companyId,
                        () -> jdbc.sql("DELETE FROM submission_key WHERE created_at < ?").param(ts(before)).update()));
            }
            catch (RuntimeException ex) {
                log.warn("The submission keys of company {} could not be swept", companyId, ex);
            }
        }
        return deleted;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
