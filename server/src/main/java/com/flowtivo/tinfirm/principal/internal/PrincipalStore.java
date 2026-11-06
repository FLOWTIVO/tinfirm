package com.flowtivo.tinfirm.principal.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalKind;
import com.flowtivo.tinfirm.principal.PrincipalStatus;

/**
 * SQL access to {@code principal} and {@code service_identity_user} (V001), under row-level security: the principals
 * of the package 1 contract, §3.1 and §3.2.
 */
@Repository
class PrincipalStore {

    private static final String COLUMNS = "id, company_id, kind, user_id, name, description, status, created_at,"
            + " updated_at, created_by";

    private final JdbcClient jdbc;

    PrincipalStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A principal row. */
    record PrincipalRow(UUID id, UUID companyId, PrincipalKind kind, @Nullable UUID userId, @Nullable String name,
            @Nullable String description, PrincipalStatus status, Instant createdAt, Instant updatedAt,
            @Nullable UUID createdBy) {

        PrincipalInfo info() {
            return new PrincipalInfo(id, companyId, kind, userId, name, status);
        }

        PrincipalRow withStatus(PrincipalStatus next, Instant at) {
            return new PrincipalRow(id, companyId, kind, userId, name, description, next, createdAt, at, createdBy);
        }
    }

    void insert(PrincipalRow row) {
        jdbc.sql("""
                INSERT INTO principal (id, company_id, kind, user_id, name, description, status, created_at,
                                       updated_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(row.id(), row.companyId(), row.kind().name(), row.userId(), row.name(), row.description(),
                    row.status().name(), ts(row.createdAt()), ts(row.updatedAt()), row.createdBy())
            .update();
    }

    Optional<PrincipalRow> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM principal WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(PrincipalStore::row)
            .optional();
    }

    /**
     * The principal, its row locked {@code FOR KEY SHARE}: against its deletion ({@code FOR UPDATE}), not against a
     * change of its status or its users or its leaving ({@code FOR NO KEY UPDATE}), nor against another module's rows
     * that name it.
     */
    Optional<PrincipalRow> lockShared(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM principal WHERE company_id = ? AND id = ? FOR KEY SHARE")
            .params(companyId, id)
            .query(PrincipalStore::row)
            .optional();
    }

    /**
     * The service identity, locked {@code FOR NO KEY UPDATE}, as every change but its deletion locks a principal: no
     * principal's id changes, so the foreign keys' checks of the rows that name it (a conversation's payer, a run's, a
     * model key's, written a second time in a transaction) never wait on it (CONVENTIONS §10). Empty for a member's
     * principal.
     */
    Optional<PrincipalRow> lockService(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM principal WHERE company_id = ? AND id = ? AND kind = 'SERVICE'"
                + " FOR NO KEY UPDATE")
            .params(companyId, id)
            .query(PrincipalStore::row)
            .optional();
    }

    /**
     * The service identity, locked {@code FOR UPDATE} for its deletion, which the row's {@code DELETE} would take
     * anyway: from the start, so that no row that names it is written meanwhile. Empty for a member's principal.
     */
    Optional<PrincipalRow> lockServiceForDeletion(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM principal WHERE company_id = ? AND id = ? AND kind = 'SERVICE'"
                + " FOR UPDATE")
            .params(companyId, id)
            .query(PrincipalStore::row)
            .optional();
    }

    Optional<PrincipalRow> findService(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM principal WHERE company_id = ? AND id = ? AND kind = 'SERVICE'")
            .params(companyId, id)
            .query(PrincipalStore::row)
            .optional();
    }

    Map<UUID, PrincipalRow> findAll(UUID companyId, Collection<UUID> ids) {
        Map<UUID, PrincipalRow> rows = new HashMap<>();
        if (ids.isEmpty()) {
            return rows;
        }
        jdbc.sql("SELECT " + COLUMNS + " FROM principal WHERE company_id = :companyId AND id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(ids))
            .query(PrincipalStore::row)
            .list()
            .forEach(row -> rows.put(row.id(), row));
        return rows;
    }

    /** Every member principal of these users in the company, those who left included. */
    List<PrincipalRow> ofUsers(UUID companyId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("SELECT " + COLUMNS + " FROM principal WHERE company_id = :companyId AND kind = 'MEMBER'"
                + " AND user_id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(userIds))
            .query(PrincipalStore::row)
            .list();
    }

    /** The ids of the company's principals that have not left, members and service identities. */
    List<UUID> currentIds(UUID companyId) {
        return jdbc.sql("SELECT id FROM principal WHERE company_id = ? AND status <> 'LEFT' ORDER BY id")
            .param(companyId)
            .query(UUID.class)
            .list();
    }

    /**
     * The member's principal that has not left, locked {@code FOR NO KEY UPDATE} when {@code forUpdate} (its leaving
     * changes its status, never its id or the row's existence: a conversation's second write, which checks its person's
     * principal again, never waits on it).
     */
    Optional<PrincipalRow> ofMember(UUID companyId, UUID userId, boolean forUpdate) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM principal WHERE company_id = ? AND user_id = ?"
                + " AND kind = 'MEMBER' AND status <> 'LEFT'" + (forUpdate ? " FOR NO KEY UPDATE" : ""))
            .params(companyId, userId)
            .query(PrincipalStore::row)
            .optional();
    }

    /** The principals of these members that have not left, by user. */
    Map<UUID, UUID> memberPrincipalIds(UUID companyId, Collection<UUID> userIds) {
        Map<UUID, UUID> ids = new HashMap<>();
        if (userIds.isEmpty()) {
            return ids;
        }
        jdbc.sql("SELECT user_id, id FROM principal WHERE company_id = :companyId AND kind = 'MEMBER'"
                + " AND status <> 'LEFT' AND user_id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(userIds))
            .query(rs -> {
                ids.put(rs.getObject("user_id", UUID.class), rs.getObject("id", UUID.class));
            });
        return ids;
    }

    /** Service identities ordered by (name, id), after the given position. */
    List<PrincipalRow> servicePage(UUID companyId, @Nullable String afterName, @Nullable UUID afterId, int limit) {
        List<Object> params = new ArrayList<>(List.of(companyId));
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM principal WHERE company_id = ? AND kind = 'SERVICE'");
        if (afterName != null && afterId != null) {
            sql.append(" AND (name, id) > (?, ?)");
            params.add(afterName);
            params.add(afterId);
        }
        sql.append(" ORDER BY name, id LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString()).params(params).query(PrincipalStore::row).list();
    }

    boolean serviceNameTaken(UUID companyId, String name, @Nullable UUID exceptId) {
        return jdbc.sql("""
                SELECT count(*) FROM principal
                 WHERE company_id = ? AND kind = 'SERVICE' AND lower(name) = lower(?) AND id IS DISTINCT FROM ?""")
            .params(companyId, name, exceptId)
            .query(Long.class)
            .single() > 0;
    }

    void updateService(UUID companyId, UUID id, String name, @Nullable String description, Instant at) {
        jdbc.sql("""
                UPDATE principal SET name = ?, description = ?, updated_at = ?
                 WHERE company_id = ? AND id = ? AND kind = 'SERVICE'""")
            .params(name, description, ts(at), companyId, id)
            .update();
    }

    /** Sets a status other than SUSPENDED; a suspension's columns go with it (principal_suspended_ck). */
    void updateStatus(UUID companyId, UUID id, PrincipalStatus status, Instant at) {
        if (status == PrincipalStatus.SUSPENDED) {
            throw new IllegalArgumentException("A suspension is stored with suspend()");
        }
        jdbc.sql("""
                UPDATE principal SET status = ?, updated_at = ?, suspended_at = NULL, suspended_by = NULL,
                       suspended_reason = NULL, suspended_cause = NULL
                 WHERE company_id = ? AND id = ?""")
            .params(status.name(), ts(at), companyId, id)
            .update();
    }

    /** Suspends the principal (the package 6 contract, §3.3): who, why, the cause. */
    void suspend(UUID companyId, UUID id, Instant at, @Nullable UUID by, @Nullable String reason, String cause) {
        jdbc.sql("""
                UPDATE principal SET status = 'SUSPENDED', updated_at = ?, suspended_at = ?, suspended_by = ?,
                       suspended_reason = ?, suspended_cause = ?
                 WHERE company_id = ? AND id = ?""")
            .params(ts(at), ts(at), by, reason, cause, companyId, id)
            .update();
    }

    /** A principal's suspension columns. */
    record SuspensionRow(UUID id, PrincipalKind kind, @Nullable UUID userId, @Nullable String name,
            PrincipalStatus status, @Nullable Instant since, @Nullable UUID by, @Nullable String reason,
            @Nullable String cause) {
    }

    private static final String SUSPENSION_COLUMNS = "id, kind, user_id, name, status, suspended_at, suspended_by,"
            + " suspended_reason, suspended_cause";

    Optional<SuspensionRow> suspension(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + SUSPENSION_COLUMNS + " FROM principal WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(PrincipalStore::suspensionRow)
            .optional();
    }

    /** The company's suspended principals, newest suspension first. */
    List<SuspensionRow> suspended(UUID companyId) {
        return jdbc.sql("SELECT " + SUSPENSION_COLUMNS + " FROM principal WHERE company_id = ? AND status = 'SUSPENDED'"
                + " ORDER BY suspended_at DESC, id")
            .param(companyId)
            .query(PrincipalStore::suspensionRow)
            .list();
    }

    /** The users of the company's suspended members. */
    Set<UUID> suspendedUsers(UUID companyId) {
        return new HashSet<>(jdbc.sql("""
                SELECT user_id FROM principal WHERE company_id = ? AND kind = 'MEMBER' AND status = 'SUSPENDED'""")
            .param(companyId)
            .query(UUID.class)
            .list());
    }

    boolean memberSuspended(UUID companyId, UUID userId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM principal
                                WHERE company_id = ? AND user_id = ? AND kind = 'MEMBER' AND status = 'SUSPENDED')""")
            .params(companyId, userId)
            .query(Boolean.class)
            .single();
    }

    private static SuspensionRow suspensionRow(ResultSet rs, int row) throws SQLException {
        OffsetDateTime since = rs.getObject("suspended_at", OffsetDateTime.class);
        return new SuspensionRow(rs.getObject("id", UUID.class), PrincipalKind.valueOf(rs.getString("kind")),
                rs.getObject("user_id", UUID.class), rs.getString("name"),
                PrincipalStatus.valueOf(rs.getString("status")), since != null ? since.toInstant() : null,
                rs.getObject("suspended_by", UUID.class), rs.getString("suspended_reason"),
                rs.getString("suspended_cause"));
    }

    // ---- A member's departure -----------------------------------------------------------------------------------

    void recordDepartureGroups(UUID companyId, UUID principalId, Collection<UUID> groupIds) {
        for (UUID groupId : groupIds) {
            jdbc.sql("""
                    INSERT INTO principal_departure_group (company_id, principal_id, group_id) VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING""")
                .params(companyId, principalId, groupId)
                .update();
        }
    }

    /** A member's principal that left. */
    record DepartureRow(UUID id, UUID userId, Instant leftAt, @Nullable Instant closedAt, @Nullable UUID closedBy) {
    }

    private static final String DEPARTURE_COLUMNS = "id, user_id, updated_at, departure_closed_at, departure_closed_by";

    Optional<DepartureRow> departure(UUID companyId, UUID id, boolean forUpdate) {
        return jdbc.sql("SELECT " + DEPARTURE_COLUMNS + " FROM principal WHERE company_id = ? AND id = ?"
                + " AND kind = 'MEMBER' AND status = 'LEFT'" + (forUpdate ? " FOR NO KEY UPDATE" : ""))
            .params(companyId, id)
            .query(PrincipalStore::departureRow)
            .optional();
    }

    List<DepartureRow> openDepartures(UUID companyId, int limit) {
        return jdbc.sql("SELECT " + DEPARTURE_COLUMNS + " FROM principal WHERE company_id = ? AND kind = 'MEMBER'"
                + " AND status = 'LEFT' AND departure_closed_at IS NULL ORDER BY updated_at DESC, id LIMIT ?")
            .params(companyId, limit)
            .query(PrincipalStore::departureRow)
            .list();
    }

    Set<UUID> departureGroups(UUID companyId, UUID principalId) {
        return new HashSet<>(jdbc.sql("""
                SELECT group_id FROM principal_departure_group WHERE company_id = ? AND principal_id = ?""")
            .params(companyId, principalId)
            .query(UUID.class)
            .list());
    }

    void closeDeparture(UUID companyId, UUID id, Instant at, @Nullable UUID by) {
        jdbc.sql("UPDATE principal SET departure_closed_at = ?, departure_closed_by = ? WHERE company_id = ? AND id = ?")
            .params(ts(at), by, companyId, id)
            .update();
    }

    private static DepartureRow departureRow(ResultSet rs, int row) throws SQLException {
        OffsetDateTime closed = rs.getObject("departure_closed_at", OffsetDateTime.class);
        return new DepartureRow(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(),
                closed != null ? closed.toInstant() : null, rs.getObject("departure_closed_by", UUID.class));
    }

    /** Deletes a service identity; who may use it goes with it (ON DELETE CASCADE). */
    void deleteService(UUID companyId, UUID id) {
        jdbc.sql("DELETE FROM principal WHERE company_id = ? AND id = ? AND kind = 'SERVICE'")
            .params(companyId, id)
            .update();
    }

    // ---- Who may use a service identity --------------------------------------------------------------------------

    /** The groups that may use the service identity. */
    Set<UUID> users(UUID companyId, UUID principalId) {
        return new HashSet<>(jdbc.sql("""
                SELECT group_id FROM service_identity_user WHERE company_id = ? AND principal_id = ?""")
            .params(companyId, principalId)
            .query(UUID.class)
            .list());
    }

    /** The groups that may use each of the service identities; one no group may use is absent. */
    Map<UUID, Set<UUID>> users(UUID companyId, Collection<UUID> principalIds) {
        Map<UUID, Set<UUID>> users = new HashMap<>();
        if (principalIds.isEmpty()) {
            return users;
        }
        jdbc.sql("""
                SELECT principal_id, group_id FROM service_identity_user
                 WHERE company_id = :companyId AND principal_id IN (:ids)""")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(principalIds))
            .query(rs -> {
                users.computeIfAbsent(rs.getObject("principal_id", UUID.class), id -> new HashSet<>())
                    .add(rs.getObject("group_id", UUID.class));
            });
        return users;
    }

    void addUsers(UUID companyId, UUID principalId, Collection<UUID> groupIds) {
        for (UUID groupId : groupIds) {
            jdbc.sql("INSERT INTO service_identity_user (company_id, principal_id, group_id) VALUES (?, ?, ?)")
                .params(companyId, principalId, groupId)
                .update();
        }
    }

    void removeUsers(UUID companyId, UUID principalId, Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return;
        }
        jdbc.sql("""
                DELETE FROM service_identity_user
                 WHERE company_id = :companyId AND principal_id = :principalId AND group_id IN (:ids)""")
            .param("companyId", companyId)
            .param("principalId", principalId)
            .param("ids", Set.copyOf(groupIds))
            .update();
    }

    /** The service identities the group may use, locked, for the group's deletion. */
    List<PrincipalRow> lockServicesUsableBy(UUID companyId, UUID groupId) {
        return jdbc.sql("""
                SELECT p.id, p.company_id, p.kind, p.user_id, p.name, p.description, p.status, p.created_at,
                       p.updated_at, p.created_by
                  FROM principal p
                  JOIN service_identity_user u ON u.principal_id = p.id AND u.company_id = p.company_id
                 WHERE p.company_id = ? AND u.group_id = ?
                 ORDER BY p.id
                   FOR NO KEY UPDATE OF p""")
            .params(companyId, groupId)
            .query(PrincipalStore::row)
            .list();
    }

    /** The service identities the group may use, by (name, id): their ids and names, in one query. */
    List<Map.Entry<UUID, String>> serviceNamesUsableBy(UUID companyId, UUID groupId) {
        return jdbc.sql("""
                SELECT p.id, p.name FROM principal p
                  JOIN service_identity_user u ON u.principal_id = p.id AND u.company_id = p.company_id
                 WHERE u.company_id = ? AND u.group_id = ? AND p.kind = 'SERVICE'
                 ORDER BY p.name, p.id""")
            .params(companyId, groupId)
            .query((rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("name")))
            .list();
    }

    /** The service identities that any of the groups may use. */
    Set<UUID> usableByGroups(UUID companyId, Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                SELECT DISTINCT principal_id FROM service_identity_user
                 WHERE company_id = :companyId AND group_id IN (:ids)""")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(groupIds))
            .query(UUID.class)
            .list());
    }

    /** Every service identity of the company. */
    Set<UUID> services(UUID companyId) {
        return new HashSet<>(jdbc.sql("SELECT id FROM principal WHERE company_id = ? AND kind = 'SERVICE'")
            .param(companyId)
            .query(UUID.class)
            .list());
    }

    private static PrincipalRow row(ResultSet rs, int row) throws SQLException {
        return new PrincipalRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                PrincipalKind.valueOf(rs.getString("kind")), rs.getObject("user_id", UUID.class), rs.getString("name"),
                rs.getString("description"), PrincipalStatus.valueOf(rs.getString("status")),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant(), rs.getObject("created_by", UUID.class));
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
