package com.flowtivo.tinfirm.company.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.identity.UserMembership;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;

/**
 * Read queries (keyset pagination) and locks. Row-level security filters every statement; the explicit
 * membership predicates are the application-level check on top of it.
 */
@Repository
class CompanyQueries {

    private final JdbcClient jdbc;

    CompanyQueries(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A company as seen by one user; {@code myRole} is null when the user is not a member. */
    record CompanyRow(UUID id, String name, @Nullable String key, Instant createdAt, @Nullable CompanyRole myRole) {
    }

    /** A company as the deployment's declarations see it (ADR-0075). */
    record DeclaredRow(UUID id, String name, @Nullable String key) {
    }

    /**
     * A membership row; user details come from the identity module, the suspension and the principal from the
     * principal module ({@code MemberStatuses}).
     */
    record MemberRow(UUID userId, CompanyRole role, RoleSource roleSource, Instant createdAt, boolean suspended,
            @Nullable UUID principalId) {

        MemberRow with(boolean isSuspended, @Nullable UUID principal) {
            return new MemberRow(userId, role, roleSource, createdAt, isSuspended, principal);
        }
    }

    /** A new user who joined as VIEWER for want of a seat (ADR-0077), for the admins' inbox. */
    void seatFallback(UUID companyId, UUID userId, CompanyRole role, String provider, Instant joinedAt) {
        jdbc.sql("INSERT INTO seat_fallback (company_id, user_id, role, provider, joined_at) VALUES (?, ?, ?, ?, ?)"
                + " ON CONFLICT DO NOTHING")
            .params(companyId, userId, role.name(), provider, OffsetDateTime.ofInstant(joinedAt, java.time.ZoneOffset.UTC))
            .update();
    }

    /**
     * A member whose provider or SCIM gives a seat role became, or stays, VIEWER for want of a seat (ADR-0088): one
     * item per person, which starts again ({@code again}) when they held a seat role in between.
     */
    void seatFallbackAgain(UUID companyId, UUID userId, CompanyRole role, String provider, Instant at, boolean again) {
        jdbc.sql("""
                INSERT INTO seat_fallback (company_id, user_id, role, provider, joined_at) VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (company_id, user_id) DO UPDATE
                   SET role = EXCLUDED.role, provider = EXCLUDED.provider,
                       joined_at = CASE WHEN ? THEN EXCLUDED.joined_at ELSE seat_fallback.joined_at END""")
            .params(companyId, userId, role.name(), provider, OffsetDateTime.ofInstant(at, ZoneOffset.UTC), again)
            .update();
    }

    /** The member's role is given in full now: no admins' item waits for it (ADR-0088). */
    void clearSeatFallback(UUID companyId, UUID userId) {
        jdbc.sql("DELETE FROM seat_fallback WHERE company_id = ? AND user_id = ?").params(companyId, userId).update();
    }

    /** The company's users who joined as VIEWER for want of a seat since {@code since}, and are Viewers still. */
    List<com.flowtivo.tinfirm.company.SeatFallbacks.Fallback> seatFallbacks(UUID companyId, Instant since,
            int limit) {
        return jdbc.sql("""
                SELECT f.user_id, f.role, f.provider, f.joined_at
                  FROM seat_fallback f
                  JOIN company_membership m ON m.company_id = f.company_id AND m.user_id = f.user_id
                 WHERE f.company_id = ? AND f.joined_at >= ? AND m.role = 'VIEWER'
                 ORDER BY f.joined_at, f.user_id
                 LIMIT ?""")
            .params(companyId, OffsetDateTime.ofInstant(since, java.time.ZoneOffset.UTC), limit)
            .query((rs, row) -> new com.flowtivo.tinfirm.company.SeatFallbacks.Fallback(
                    rs.getObject("user_id", UUID.class), CompanyRole.valueOf(rs.getString("role")),
                    rs.getString("provider"), instant(rs, "joined_at")))
            .list();
    }

    /** The companies with a membership whose role a provider or SCIM decides (ADR-0088), instance-wide. */
    List<UUID> companiesWithManagedRoles() {
        return jdbc.sql("SELECT DISTINCT company_id FROM company_membership WHERE role_source <> 'APP' ORDER BY 1")
            .query(UUID.class)
            .list();
    }

    /** The company's OWNERs. */
    List<UUID> owners(UUID companyId) {
        return jdbc.sql("SELECT user_id FROM company_membership WHERE company_id = ? AND role = 'OWNER'")
            .param(companyId)
            .query(UUID.class)
            .list();
    }

    /** Companies visible to the user (all of them for an instance admin), ordered by (name, id). */
    List<CompanyRow> companies(UUID userId, boolean instanceAdmin, @Nullable String afterName,
            @Nullable UUID afterId, int limit) {
        List<Object> params = new ArrayList<>(List.of(userId, instanceAdmin));
        StringBuilder sql = new StringBuilder("""
                SELECT c.id, c.name, c.key, c.created_at, m.role AS my_role
                  FROM company c
                  LEFT JOIN company_membership m ON m.company_id = c.id AND m.user_id = ?
                 WHERE (? OR m.user_id IS NOT NULL)""");
        if (afterName != null && afterId != null) {
            sql.append(" AND (c.name, c.id) > (?, ?)");
            params.add(afterName);
            params.add(afterId);
        }
        sql.append(" ORDER BY c.name, c.id LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(CompanyQueries::company).list();
    }

    Optional<CompanyRow> company(UUID companyId, UUID userId) {
        return jdbc.sql("""
                SELECT c.id, c.name, c.key, c.created_at, m.role AS my_role
                  FROM company c
                  LEFT JOIN company_membership m ON m.company_id = c.id AND m.user_id = ?
                 WHERE c.id = ?""")
            .params(userId, companyId)
            .query(CompanyQueries::company)
            .optional();
    }

    /** The company the deployment declares by this key. */
    Optional<UUID> companyIdByKey(String key) {
        return jdbc.sql("SELECT id FROM company WHERE key = ?").param(key).query(UUID.class).optional();
    }

    Optional<DeclaredRow> declaredRow(UUID companyId) {
        return jdbc.sql("SELECT id, name, key FROM company WHERE id = ?")
            .param(companyId)
            .query(CompanyQueries::declared)
            .optional();
    }

    /** The companies without a key that have exactly this name: a declaration may adopt one (ADR-0075). */
    List<DeclaredRow> keylessNamed(String name) {
        return jdbc.sql("SELECT id, name, key FROM company WHERE key IS NULL AND name = ? ORDER BY id")
            .param(name)
            .query(CompanyQueries::declared)
            .list();
    }

    /** The companies no declaration names: without a key, or with a key that is no longer declared. */
    List<DeclaredRow> undeclared(Collection<String> keys) {
        return jdbc.sql("SELECT id, name, key FROM company WHERE key IS NULL OR NOT (key = ANY (?)) ORDER BY name, id")
            .param(keys.toArray(String[]::new))
            .query(CompanyQueries::declared)
            .list();
    }

    void setKey(UUID companyId, String key, Instant now) {
        jdbc.sql("UPDATE company SET key = ?, updated_at = ?, version = version + 1 WHERE id = ? AND key IS NULL")
            .params(key, now.atOffset(ZoneOffset.UTC), companyId)
            .update();
    }

    /** The user's role in the company; readable through the "own memberships" policy before any company context. */
    Optional<CompanyRole> roleOf(UUID companyId, UUID userId) {
        return jdbc.sql("SELECT role FROM company_membership WHERE company_id = ? AND user_id = ?")
            .params(companyId, userId)
            .query((rs, row) -> CompanyRole.valueOf(rs.getString("role")))
            .optional();
    }

    /**
     * Serialises membership changes of one company (last-owner checks) until the transaction ends.
     * {@code FOR NO KEY UPDATE} does not block the {@code FOR KEY SHARE} locks of foreign-key checks, so audit
     * inserts for this company (also from other transactions) are never blocked by it.
     */
    void lockCompany(UUID companyId) {
        jdbc.sql("SELECT id FROM company WHERE id = ? FOR NO KEY UPDATE").param(companyId).query(UUID.class).optional();
    }

    List<MemberRow> members(UUID companyId, @Nullable Instant afterCreatedAt, @Nullable UUID afterUserId, int limit) {
        List<Object> params = new ArrayList<>(List.of(companyId));
        StringBuilder sql = new StringBuilder(
                "SELECT user_id, role, role_source, created_at FROM company_membership WHERE company_id = ?");
        if (afterCreatedAt != null && afterUserId != null) {
            sql.append(" AND (created_at, user_id) > (?, ?)");
            params.add(afterCreatedAt.atOffset(ZoneOffset.UTC));
            params.add(afterUserId);
        }
        sql.append(" ORDER BY created_at, user_id LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString())
            .params(params)
            .query((rs, row) -> new MemberRow(rs.getObject("user_id", UUID.class),
                    CompanyRole.valueOf(rs.getString("role")), RoleSource.parse(rs.getString("role_source")),
                    instant(rs, "created_at"), false, null))
            .list();
    }

    List<UserMembership> membershipsOf(UUID userId) {
        return jdbc.sql("""
                SELECT m.company_id, c.name, m.role
                  FROM company_membership m
                  JOIN company c ON c.id = m.company_id
                 WHERE m.user_id = ?
                 ORDER BY c.name, c.id""")
            .param(userId)
            .query((rs, row) -> new UserMembership(rs.getObject("company_id", UUID.class), rs.getString("name"),
                    CompanyRole.valueOf(rs.getString("role"))))
            .list();
    }

    private static CompanyRow company(ResultSet rs, int row) throws SQLException {
        String role = rs.getString("my_role");
        return new CompanyRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("key"),
                instant(rs, "created_at"), role != null ? CompanyRole.valueOf(role) : null);
    }

    private static DeclaredRow declared(ResultSet rs, int row) throws SQLException {
        return new DeclaredRow(rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("key"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }
}
