package com.flowtivo.tinfirm.model.internal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.model.ModelRelay;

/**
 * SQL access to {@code run_model_token} (V002), under row-level security, the cross-company lookup of a token's
 * company ({@code run_model_token_company}), and the tokens themselves: {@code tfm_} and 32 random bytes in base64url
 * (47 characters), of which only {@code hex(sha256(token))} is stored.
 */
@Repository
class RelayTokenStore {

    private static final String PREFIX = "tfm_";

    private static final Pattern FORMAT = Pattern.compile("^tfm_[A-Za-z0-9_-]{43}$");

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final String COLUMNS = """
            token_hash, company_id, run_id, epoch, runner_host_id, paying_principal_id, format, conversation_id,
            agent_id, revoked_at""";

    private final JdbcClient jdbc;

    RelayTokenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The relay's two formats, as the token's root. */
    enum RelayFormat {
        ANTHROPIC, OPENAI
    }

    /** A token's row. */
    record TokenRow(String tokenHash, UUID companyId, UUID runId, long epoch, UUID runnerHostId,
            UUID payingPrincipalId, RelayFormat format, @Nullable UUID conversationId, @Nullable UUID agentId,
            @Nullable Instant revokedAt) {
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

    /** The token of an {@code Authorization: Bearer} header, if it has the token's format. */
    static Optional<String> fromAuthorization(@Nullable String header) {
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.empty();
        }
        String token = header.substring(7).trim();
        return FORMAT.matcher(token).matches() ? Optional.of(token) : Optional.empty();
    }

    void insert(String tokenHash, ModelRelay.RelayedRun run, RelayFormat format, Instant now) {
        jdbc.sql("""
                INSERT INTO run_model_token (token_hash, company_id, run_id, epoch, runner_host_id,
                                             paying_principal_id, format, conversation_id, agent_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(tokenHash, run.companyId(), run.runId(), run.epoch(), run.runnerHostId(),
                    run.payingPrincipalId(), format.name(), run.conversationId(), run.agentId(), ts(now))
            .update();
    }

    /** Revokes the run's tokens that are not revoked yet. */
    int revokeRun(UUID companyId, UUID runId, Instant now) {
        return jdbc.sql("""
                UPDATE run_model_token SET revoked_at = ?
                 WHERE company_id = ? AND run_id = ? AND revoked_at IS NULL""")
            .params(ts(now), companyId, runId)
            .update();
    }

    /** Revokes the run's tokens of this epoch and of earlier ones that are not revoked yet; a later epoch's stay. */
    int revokeUpTo(UUID companyId, UUID runId, long epoch, Instant now) {
        return jdbc.sql("""
                UPDATE run_model_token SET revoked_at = ?
                 WHERE company_id = ? AND run_id = ? AND epoch <= ? AND revoked_at IS NULL""")
            .params(ts(now), companyId, runId, epoch)
            .update();
    }

    /** Revokes the run's tokens of this epoch that are not revoked yet. */
    int revokeEpoch(UUID companyId, UUID runId, long epoch, Instant now) {
        return jdbc.sql("""
                UPDATE run_model_token SET revoked_at = ?
                 WHERE company_id = ? AND run_id = ? AND epoch = ? AND revoked_at IS NULL""")
            .params(ts(now), companyId, runId, epoch)
            .update();
    }

    /** The company of a token, across companies (its id only); runs without a company context. */
    Optional<UUID> companyOf(String tokenHash) {
        return jdbc.sql("SELECT company_id FROM run_model_token_company(?)")
            .param(tokenHash)
            .query(UUID.class)
            .optional();
    }

    Optional<TokenRow> find(UUID companyId, String tokenHash) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM run_model_token WHERE company_id = ? AND token_hash = ?")
            .params(companyId, tokenHash)
            .query(RelayTokenStore::row)
            .optional();
    }

    /** Notes a request admitted with the token, before it is forwarded; {@link #count} notes its end. */
    void admitted(UUID companyId, String tokenHash) {
        jdbc.sql("UPDATE run_model_token SET admitted_count = admitted_count + 1 WHERE company_id = ? AND token_hash = ?")
            .params(companyId, tokenHash)
            .update();
    }

    /** Adds a relayed request to the token's counters. */
    void count(UUID companyId, String tokenHash, long requestBytes, long responseBytes) {
        jdbc.sql("""
                UPDATE run_model_token
                   SET request_count = request_count + 1, request_bytes = request_bytes + ?,
                       response_bytes = response_bytes + ?
                 WHERE company_id = ? AND token_hash = ?""")
            .params(requestBytes, responseBytes, companyId, tokenHash)
            .update();
    }

    /** Notes the model the gateway answered it does not serve, for a request with the token. */
    void notServed(UUID companyId, String tokenHash, String model) {
        jdbc.sql("UPDATE run_model_token SET model_not_served = ? WHERE company_id = ? AND token_hash = ?")
            .params(model, companyId, tokenHash)
            .update();
    }

    /** The model the gateway last answered it does not serve for the run's epoch, over its tokens, the newest first. */
    Optional<String> modelNotServed(UUID companyId, UUID runId, long epoch) {
        return jdbc.sql("""
                SELECT model_not_served FROM run_model_token
                 WHERE company_id = ? AND run_id = ? AND epoch = ? AND model_not_served IS NOT NULL
                 ORDER BY created_at DESC LIMIT 1""")
            .params(companyId, runId, epoch)
            .query(String.class)
            .optional();
    }

    ModelRelay.RelayUsage usage(UUID companyId, UUID runId) {
        return jdbc.sql("""
                SELECT coalesce(sum(request_count), 0) AS requests, coalesce(sum(request_bytes), 0) AS request_bytes,
                       coalesce(sum(response_bytes), 0) AS response_bytes
                  FROM run_model_token WHERE company_id = ? AND run_id = ?""")
            .params(companyId, runId)
            .query((rs, row) -> new ModelRelay.RelayUsage(rs.getLong("requests"), rs.getLong("request_bytes"),
                    rs.getLong("response_bytes")))
            .single();
    }

    /**
     * The run's requests admitted and not yet counted, over its tokens: their answers are under way. A token from
     * before the admission count (requests counted, none admitted) counts none.
     */
    long answersUnderWay(UUID companyId, UUID runId) {
        return jdbc.sql("""
                SELECT coalesce(sum(greatest(admitted_count - request_count, 0)), 0)
                  FROM run_model_token WHERE company_id = ? AND run_id = ?""")
            .params(companyId, runId)
            .query(Long.class)
            .single();
    }

    /** Deletes the company's tokens revoked before {@code before}. */
    int sweep(UUID companyId, Instant before) {
        return jdbc.sql("DELETE FROM run_model_token WHERE company_id = ? AND revoked_at < ?")
            .params(companyId, ts(before))
            .update();
    }

    private static TokenRow row(ResultSet rs, int row) throws SQLException {
        OffsetDateTime revoked = rs.getObject("revoked_at", OffsetDateTime.class);
        return new TokenRow(rs.getString("token_hash"), rs.getObject("company_id", UUID.class),
                rs.getObject("run_id", UUID.class), rs.getLong("epoch"), rs.getObject("runner_host_id", UUID.class),
                rs.getObject("paying_principal_id", UUID.class), RelayFormat.valueOf(rs.getString("format")),
                rs.getObject("conversation_id", UUID.class), rs.getObject("agent_id", UUID.class),
                revoked != null ? revoked.toInstant() : null);
    }

    private static @Nullable OffsetDateTime ts(@Nullable Instant instant) {
        return instant != null ? instant.atOffset(ZoneOffset.UTC) : null;
    }
}
