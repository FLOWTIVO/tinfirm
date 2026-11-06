package com.flowtivo.tinfirm.company.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.scim.ScimTokenStore;
import com.flowtivo.tinfirm.shared.directory.ScimTokenDisplayNames;

/** The company's SCIM tokens in PostgreSQL ({@link ScimTokenStore}), and their names for the audit log. */
@Component
class JdbcScimTokenStore implements ScimTokenStore, ScimTokenDisplayNames {

    private static final String COLUMNS = "id, company_id, name, created_by, created_at, expires_at, last_used_at,"
            + " revoked_at";

    private final JdbcClient jdbc;

    JdbcScimTokenStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The token whose hash this is, across companies; outside any tenant context. */
    @Override
    @Transactional(readOnly = true)
    public Optional<Resolved> resolve(String tokenHash) {
        return jdbc.sql("SELECT id, company_id, expires_at, revoked_at FROM scim_token_by_hash(?)")
            .param(tokenHash)
            .query((rs, row) -> new Resolved(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                    instant(rs, "expires_at"), instant(rs, "revoked_at")))
            .optional();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public List<TokenRow> list(UUID companyId, int limit) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM scim_token WHERE company_id = ? ORDER BY created_at DESC, id"
                + " LIMIT ?")
            .params(companyId, limit)
            .query(JdbcScimTokenStore::row)
            .list();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public Optional<TokenRow> find(UUID companyId, UUID tokenId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM scim_token WHERE company_id = ? AND id = ?")
            .params(companyId, tokenId)
            .query(JdbcScimTokenStore::row)
            .optional();
    }

    /** The company's tokens neither revoked nor expired; the caller holds the company's lock. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    public int countActive(UUID companyId, Instant now) {
        return jdbc.sql("""
                SELECT count(*) FROM scim_token
                 WHERE company_id = ? AND revoked_at IS NULL AND (expires_at IS NULL OR expires_at > ?)""")
            .params(companyId, ts(now))
            .query(Integer.class)
            .single();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(TokenRow token, String tokenHash) {
        jdbc.sql("""
                INSERT INTO scim_token (id, company_id, name, token_hash, created_by, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
            .params(token.id(), token.companyId(), token.name(), tokenHash, token.createdBy(), ts(token.createdAt()),
                    token.expiresAt() != null ? ts(token.expiresAt()) : null)
            .update();
    }

    /** Revokes the token unless it is revoked already; whether this revoked it. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean revoke(UUID companyId, UUID tokenId, Instant now, @Nullable UUID by) {
        return jdbc.sql("""
                UPDATE scim_token SET revoked_at = ?, revoked_by = ?
                 WHERE company_id = ? AND id = ? AND revoked_at IS NULL""")
            .params(ts(now), by, companyId, tokenId)
            .update() > 0;
    }

    /** {@code last_used_at}, written at most once a minute per token, so that a request is not a write. */
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void touch(UUID companyId, UUID tokenId, Instant now) {
        jdbc.sql("""
                UPDATE scim_token SET last_used_at = ?
                 WHERE company_id = ? AND id = ? AND (last_used_at IS NULL OR last_used_at < ?)""")
            .params(ts(now), companyId, tokenId, ts(now.minusSeconds(60)))
            .update();
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, String> names(Collection<UUID> tokenIds) {
        Map<UUID, String> names = new HashMap<>();
        if (tokenIds.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, name FROM scim_token WHERE id IN (:ids)")
            .param("ids", Set.copyOf(tokenIds))
            .query(rs -> {
                names.put(rs.getObject("id", UUID.class), rs.getString("name"));
            });
        return names;
    }

    private static TokenRow row(ResultSet rs, int row) throws SQLException {
        return new TokenRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name"), rs.getObject("created_by", UUID.class), instant(rs, "created_at"),
                instant(rs, "expires_at"), instant(rs, "last_used_at"), instant(rs, "revoked_at"));
    }

    private static @Nullable Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    private static OffsetDateTime ts(Instant at) {
        return at.atOffset(ZoneOffset.UTC);
    }
}
