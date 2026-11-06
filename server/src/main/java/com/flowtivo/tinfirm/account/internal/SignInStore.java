package com.flowtivo.tinfirm.account.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * SQL access to {@code oauth_authorization_request} (V001, V025): a pending OAuth sign-in (the package 1 contract,
 * §3.8), kept by the SHA-256 of its state for ten minutes and consumed once by the callback; its PKCE verifier is not
 * kept, the callback derives it from the state again ({@link SignInVerifiers}). An instance-level table without
 * row-level security, like {@code api_token}: the callback finds the request before any company context exists, and
 * consumes only a request of the caller from the same session.
 */
@Repository
class SignInStore {

    private static final String COLUMNS = "state_hash, company_id, system_id, principal_id, credential_id,"
            + " credential_name, user_id, session_hash, return_to, created_at, expires_at";

    private final JdbcClient jdbc;

    SignInStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * A pending sign-in (V049): for a member's own account, or for one of the system's credentials (ADR-0072).
     *
     * @param principalId    the member whose own account the grant becomes; null for a credential
     * @param credentialId   the credential signed in again; null otherwise
     * @param credentialName the name of the new credential the grant becomes; null otherwise
     * @param userId         the person in the browser: the member, or the admin adding the credential
     */
    record Request(String stateHash, UUID companyId, UUID systemId, @Nullable UUID principalId,
            @Nullable UUID credentialId, @Nullable String credentialName, UUID userId, String sessionHash,
            String returnTo, Instant createdAt, Instant expiresAt) {

        @Override
        public String toString() {
            return "Request[systemId=" + systemId + ", principalId=" + principalId + ", credentialId=" + credentialId
                    + ", userId=" + userId + ", expiresAt=" + expiresAt + "]";
        }
    }

    void insert(Request request) {
        jdbc.sql("INSERT INTO oauth_authorization_request (" + COLUMNS + ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")
            .params(request.stateHash(), request.companyId(), request.systemId(), request.principalId(),
                    request.credentialId(), request.credentialName(), request.userId(), request.sessionHash(),
                    request.returnTo(), AccountStore.ts(request.createdAt()), AccountStore.ts(request.expiresAt()))
            .update();
    }

    /**
     * Consumes the request of the state when it is the user's from that session: deleted and returned in one
     * statement, so two callbacks cannot both use it.
     */
    Optional<Request> consume(String stateHash, UUID userId, String sessionHash) {
        return jdbc.sql("DELETE FROM oauth_authorization_request WHERE state_hash = ? AND user_id = ?"
                + " AND session_hash = ? RETURNING " + COLUMNS)
            .params(stateHash, userId, sessionHash)
            .query(SignInStore::request)
            .optional();
    }

    /** Whether a request of the state exists, whoever's it is. */
    boolean exists(String stateHash) {
        return jdbc.sql("SELECT count(*) FROM oauth_authorization_request WHERE state_hash = ?")
            .param(stateHash)
            .query(Long.class)
            .single() > 0;
    }

    /** Drops the pending sign-ins of a principal (its deletion, its member's leaving). */
    void deleteOfPrincipal(UUID companyId, UUID principalId) {
        jdbc.sql("DELETE FROM oauth_authorization_request WHERE company_id = ? AND principal_id = ?")
            .params(companyId, principalId)
            .update();
    }

    /** Drops the pending sign-ins on a system (its removal). */
    void deleteOnSystem(UUID companyId, UUID systemId) {
        jdbc.sql("DELETE FROM oauth_authorization_request WHERE company_id = ? AND system_id = ?")
            .params(companyId, systemId)
            .update();
    }

    /** Sweeps the requests that expired; returns how many. */
    int deleteExpired(Instant now) {
        return jdbc.sql("DELETE FROM oauth_authorization_request WHERE expires_at <= ?")
            .param(AccountStore.ts(now))
            .update();
    }

    private static Request request(ResultSet rs, int row) throws SQLException {
        return new Request(rs.getString("state_hash"), rs.getObject("company_id", UUID.class),
                rs.getObject("system_id", UUID.class), rs.getObject("principal_id", UUID.class),
                rs.getObject("credential_id", UUID.class), rs.getString("credential_name"),
                rs.getObject("user_id", UUID.class), rs.getString("session_hash"), rs.getString("return_to"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("expires_at", OffsetDateTime.class).toInstant());
    }
}
