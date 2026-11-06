package com.flowtivo.tinfirm.skill.internal;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.skill.SkillInfo;
import com.flowtivo.tinfirm.skill.SkillVersionInfo;
import com.flowtivo.tinfirm.skill.Skills;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * SQL access to {@code skill}, {@code skill_version}, {@code skill_share} and {@code skill_member_share} (V040), under
 * row-level security: the library of the skills contract, §3.1 to §3.3.
 */
@Repository
class SkillStore {

    private static final String COLUMNS = "id, company_id, name, owner_principal_id, latest_version, deleted_at,"
            + " created_at, updated_at, created_by";

    private static final String VERSION_COLUMNS = "v.skill_id, v.version, s.name, v.description, v.sha256,"
            + " v.size_bytes, v.file_count, v.files, v.note, v.created_at, v.created_by";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final JdbcClient jdbc;

    SkillStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A skill row. */
    record SkillRow(UUID id, UUID companyId, String name, @Nullable UUID ownerPrincipalId, int latestVersion,
            @Nullable Instant deletedAt, Instant createdAt, Instant updatedAt, @Nullable UUID createdBy) {

        SkillInfo info() {
            return new SkillInfo(id, companyId, name, latestVersion, ownerPrincipalId, deletedAt);
        }

        boolean live() {
            return deletedAt == null;
        }
    }

    /** A version row, without its content. */
    record VersionRow(UUID skillId, int version, String name, String description, String sha256, int sizeBytes,
            int fileCount, List<SkillBundle.FileEntry> files, @Nullable String note, Instant createdAt,
            @Nullable UUID createdBy) {

        VersionRow {
            files = List.copyOf(files);
        }

        SkillVersionInfo info() {
            return new SkillVersionInfo(skillId, version, name, description, sha256, sizeBytes);
        }
    }

    /** Whom a skill is shared with: groups, and members by principal. */
    record Shares(Set<UUID> groupIds, Set<UUID> principalIds) {

        static final Shares NONE = new Shares(Set.of(), Set.of());

        Shares {
            groupIds = Set.copyOf(groupIds);
            principalIds = Set.copyOf(principalIds);
        }
    }

    /** Which skills a listing shows. */
    enum Scope {
        /** Every live skill (OWNER, ADMIN). */
        ALL,
        /** The caller's own, those shared with their groups and those shared with them. */
        VISIBLE,
        /** Every deleted skill (OWNER, ADMIN). */
        DELETED
    }

    // ---- skills ------------------------------------------------------------------------------------------------

    void insert(SkillRow row) {
        jdbc.sql("""
                INSERT INTO skill (id, company_id, name, owner_principal_id, latest_version, created_at, updated_at,
                                   created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(row.id(), row.companyId(), row.name(), row.ownerPrincipalId(), row.latestVersion(),
                    ts(row.createdAt()), ts(row.updatedAt()), row.createdBy())
            .update();
    }

    /** The skill, live or deleted. */
    Optional<SkillRow> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(SkillStore::row)
            .optional();
    }

    /** The skills, live or deleted, by id. */
    Map<UUID, SkillRow> findAll(UUID companyId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<UUID, SkillRow> rows = new LinkedHashMap<>();
        jdbc.sql("SELECT " + COLUMNS + " FROM skill WHERE company_id = :companyId AND id IN (:ids) ORDER BY name, id")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(ids))
            .query(SkillStore::row)
            .list()
            .forEach(row -> rows.put(row.id(), row));
        return rows;
    }

    Optional<SkillRow> findLive(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill WHERE company_id = ? AND id = ? AND deleted_at IS NULL")
            .params(companyId, id)
            .query(SkillStore::row)
            .optional();
    }

    /**
     * The live skill, locked: its versions, shares and delete go one at a time. {@code FOR NO KEY UPDATE}: the foreign
     * keys' checks of the rows that name a skill (a version, an agent's pin, a run's skill) never wait on it.
     */
    Optional<SkillRow> lockLive(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill WHERE company_id = ? AND id = ? AND deleted_at IS NULL"
                + " FOR NO KEY UPDATE")
            .params(companyId, id)
            .query(SkillStore::row)
            .optional();
    }

    /**
     * The live skill, locked for its delete: {@code FOR UPDATE}, which conflicts with what {@link #lockLive} conflicts
     * with and also with a change of an agent's pins ({@link #lockForPins}). So the delete counts and enumerates the
     * agents that pin the skill after every change that named it has committed, and before any that names it next
     * reads it (CONVENTIONS §10).
     */
    Optional<SkillRow> lockLiveForDelete(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill WHERE company_id = ? AND id = ? AND deleted_at IS NULL"
                + " FOR UPDATE")
            .params(companyId, id)
            .query(SkillStore::row)
            .optional();
    }

    /**
     * The skills locked for a change of an agent's pins, live or deleted, in the order of their ids:
     * {@code FOR KEY SHARE}, so only a delete ({@link #lockLiveForDelete}) waits on it or it on the delete; a new version,
     * a change of shares and a member's leaving ({@code FOR NO KEY UPDATE}) never do.
     */
    void lockForPins(UUID companyId, Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("SELECT id FROM skill WHERE company_id = :companyId AND id IN (:ids) ORDER BY id FOR KEY SHARE")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(ids))
            .query(UUID.class)
            .list();
    }

    boolean nameTaken(UUID companyId, String name) {
        return jdbc.sql("SELECT count(*) FROM skill WHERE company_id = ? AND deleted_at IS NULL AND name = ?")
            .params(companyId, name)
            .query(Long.class)
            .single() > 0;
    }

    /** A page of the skills a listing shows, by name and id, with an optional search in names and descriptions. */
    List<SkillRow> page(UUID companyId, Scope scope, @Nullable UUID principalId, Collection<UUID> groupIds,
            @Nullable String query, @Nullable String afterName, @Nullable UUID afterId, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("companyId", companyId);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS + " FROM skill k WHERE k.company_id = :companyId");
        sql.append(scope == Scope.DELETED ? " AND k.deleted_at IS NOT NULL" : " AND k.deleted_at IS NULL");
        if (scope == Scope.VISIBLE) {
            sql.append(" AND (k.owner_principal_id = :principalId");
            params.put("principalId", principalId);
            if (principalId != null) {
                sql.append(" OR EXISTS (SELECT 1 FROM skill_member_share m WHERE m.skill_id = k.id"
                        + " AND m.principal_id = :principalId)");
            }
            if (!groupIds.isEmpty()) {
                sql.append(" OR EXISTS (SELECT 1 FROM skill_share s WHERE s.skill_id = k.id"
                        + " AND s.group_id IN (:groupIds))");
                params.put("groupIds", Set.copyOf(groupIds));
            }
            sql.append(")");
        }
        if (query != null && !query.isBlank()) {
            sql.append(" AND (strpos(k.name, :query) > 0 OR EXISTS (SELECT 1 FROM skill_version v"
                    + " WHERE v.skill_id = k.id AND v.version = k.latest_version"
                    + " AND strpos(lower(v.description), :query) > 0))");
            params.put("query", query.strip().toLowerCase(java.util.Locale.ROOT));
        }
        if (afterName != null && afterId != null) {
            sql.append(" AND (k.name, k.id) > (:afterName, :afterId)");
            params.put("afterName", afterName);
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY k.name, k.id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(SkillStore::row).list();
    }

    void setLatest(UUID companyId, UUID id, int version, Instant at) {
        jdbc.sql("UPDATE skill SET latest_version = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(version, ts(at), companyId, id)
            .update();
    }

    void touch(UUID companyId, UUID id, Instant at) {
        jdbc.sql("UPDATE skill SET updated_at = ? WHERE company_id = ? AND id = ?")
            .params(ts(at), companyId, id)
            .update();
    }

    void softDelete(UUID companyId, UUID id, @Nullable UUID by, Instant at) {
        jdbc.sql("UPDATE skill SET deleted_at = ?, deleted_by = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(ts(at), by, ts(at), companyId, id)
            .update();
    }

    /** The live skills the member's principal owns, locked. */
    List<SkillRow> lockOwnedBy(UUID companyId, UUID principalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill WHERE company_id = ? AND owner_principal_id = ?"
                + " AND deleted_at IS NULL ORDER BY id FOR NO KEY UPDATE")
            .params(companyId, principalId)
            .query(SkillStore::row)
            .list();
    }

    /** Every skill of the principal passes to the admins, deleted ones included. */
    void clearOwner(UUID companyId, UUID principalId, Instant at) {
        jdbc.sql("UPDATE skill SET owner_principal_id = NULL, updated_at = ? WHERE company_id = ?"
                + " AND owner_principal_id = ?")
            .params(ts(at), companyId, principalId)
            .update();
    }

    /** The live skills shared with the group, locked. */
    List<SkillRow> lockSharedWith(UUID companyId, UUID groupId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill k WHERE k.company_id = ? AND k.deleted_at IS NULL"
                + " AND EXISTS (SELECT 1 FROM skill_share s WHERE s.skill_id = k.id AND s.group_id = ?)"
                + " ORDER BY k.id FOR NO KEY UPDATE")
            .params(companyId, groupId)
            .query(SkillStore::row)
            .list();
    }

    /** The live skills shared with the member's principal on their own, locked. */
    List<SkillRow> lockSharedWithMember(UUID companyId, UUID principalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM skill k WHERE k.company_id = ? AND k.deleted_at IS NULL"
                + " AND EXISTS (SELECT 1 FROM skill_member_share m WHERE m.skill_id = k.id AND m.principal_id = ?)"
                + " ORDER BY k.id FOR NO KEY UPDATE")
            .params(companyId, principalId)
            .query(SkillStore::row)
            .list();
    }

    // ---- versions ----------------------------------------------------------------------------------------------

    void insertVersion(UUID companyId, VersionRow row, byte[] content) {
        jdbc.sql("""
                INSERT INTO skill_version (company_id, skill_id, version, description, sha256, size_bytes, file_count,
                                           files, note, content, created_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb), ?, ?, ?, ?)""")
            .params(companyId, row.skillId(), row.version(), row.description(), row.sha256(), row.sizeBytes(),
                    row.fileCount(), JSON.writeValueAsString(row.files()), row.note(), content, ts(row.createdAt()),
                    row.createdBy())
            .update();
    }

    /** The skill's versions, newest first, without their content. */
    List<VersionRow> versions(UUID companyId, UUID skillId) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                + " WHERE v.company_id = ? AND v.skill_id = ? ORDER BY v.version DESC")
            .params(companyId, skillId)
            .query(SkillStore::version)
            .list();
    }

    Optional<VersionRow> version(UUID companyId, UUID skillId, int version) {
        return jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                + " WHERE v.company_id = ? AND v.skill_id = ? AND v.version = ?")
            .params(companyId, skillId, version)
            .query(SkillStore::version)
            .optional();
    }

    /** Versions of skills, by key; one that does not exist is left out. */
    Map<Skills.SkillVersionKey, VersionRow> versions(UUID companyId, Collection<Skills.SkillVersionKey> keys) {
        if (keys.isEmpty()) {
            return Map.of();
        }
        Set<UUID> skillIds = new HashSet<>();
        keys.forEach(key -> skillIds.add(key.skillId()));
        Set<Skills.SkillVersionKey> wanted = Set.copyOf(keys);
        Map<Skills.SkillVersionKey, VersionRow> rows = new HashMap<>();
        jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                + " WHERE v.company_id = :companyId AND v.skill_id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", skillIds)
            .query(SkillStore::version)
            .list()
            .forEach(row -> {
                Skills.SkillVersionKey key = new Skills.SkillVersionKey(row.skillId(), row.version());
                if (wanted.contains(key)) {
                    rows.put(key, row);
                }
            });
        return rows;
    }

    /** The latest versions of skills, by skill. */
    Map<UUID, VersionRow> latest(UUID companyId, Collection<UUID> skillIds) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, VersionRow> rows = new HashMap<>();
        jdbc.sql("SELECT " + VERSION_COLUMNS + " FROM skill_version v JOIN skill s ON s.id = v.skill_id"
                + " AND s.latest_version = v.version WHERE v.company_id = :companyId AND v.skill_id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(skillIds))
            .query(SkillStore::version)
            .list()
            .forEach(row -> rows.put(row.skillId(), row));
        return rows;
    }

    Optional<byte[]> content(UUID companyId, UUID skillId, int version) {
        return jdbc.sql("SELECT content FROM skill_version WHERE company_id = ? AND skill_id = ? AND version = ?")
            .params(companyId, skillId, version)
            .query((rs, n) -> rs.getBytes("content"))
            .optional();
    }

    // ---- shares ------------------------------------------------------------------------------------------------

    Shares shares(UUID companyId, UUID skillId) {
        return shares(companyId, List.of(skillId)).getOrDefault(skillId, Shares.NONE);
    }

    /** Whom each skill is shared with; a skill shared with nobody is absent. */
    Map<UUID, Shares> shares(UUID companyId, Collection<UUID> skillIds) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> groups = new HashMap<>();
        Map<UUID, Set<UUID>> members = new HashMap<>();
        jdbc.sql("SELECT skill_id, group_id FROM skill_share WHERE company_id = :companyId AND skill_id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(skillIds))
            .query(rs -> {
                groups.computeIfAbsent(rs.getObject("skill_id", UUID.class), id -> new HashSet<>())
                    .add(rs.getObject("group_id", UUID.class));
            });
        jdbc.sql("SELECT skill_id, principal_id FROM skill_member_share WHERE company_id = :companyId"
                + " AND skill_id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(skillIds))
            .query(rs -> {
                members.computeIfAbsent(rs.getObject("skill_id", UUID.class), id -> new HashSet<>())
                    .add(rs.getObject("principal_id", UUID.class));
            });
        Map<UUID, Shares> shares = new HashMap<>();
        Set<UUID> ids = new HashSet<>(groups.keySet());
        ids.addAll(members.keySet());
        for (UUID id : ids) {
            shares.put(id, new Shares(groups.getOrDefault(id, Set.of()), members.getOrDefault(id, Set.of())));
        }
        return shares;
    }

    void addShares(UUID companyId, UUID skillId, Collection<UUID> groupIds, @Nullable UUID by, Instant at) {
        for (UUID groupId : groupIds) {
            jdbc.sql("""
                    INSERT INTO skill_share (company_id, skill_id, group_id, shared_by, shared_at)
                    VALUES (?, ?, ?, ?, ?)""")
                .params(companyId, skillId, groupId, by, ts(at))
                .update();
        }
    }

    void removeShares(UUID companyId, UUID skillId, Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM skill_share WHERE company_id = :companyId AND skill_id = :skillId"
                + " AND group_id IN (:groupIds)")
            .param("companyId", companyId)
            .param("skillId", skillId)
            .param("groupIds", Set.copyOf(groupIds))
            .update();
    }

    void addMemberShares(UUID companyId, UUID skillId, Collection<UUID> principalIds, @Nullable UUID by,
            Instant at) {
        for (UUID principalId : principalIds) {
            jdbc.sql("""
                    INSERT INTO skill_member_share (company_id, skill_id, principal_id, shared_by, shared_at)
                    VALUES (?, ?, ?, ?, ?)""")
                .params(companyId, skillId, principalId, by, ts(at))
                .update();
        }
    }

    void removeMemberShares(UUID companyId, UUID skillId, Collection<UUID> principalIds) {
        if (principalIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM skill_member_share WHERE company_id = :companyId AND skill_id = :skillId"
                + " AND principal_id IN (:principalIds)")
            .param("companyId", companyId)
            .param("skillId", skillId)
            .param("principalIds", Set.copyOf(principalIds))
            .update();
    }

    void removeAllShares(UUID companyId, UUID skillId) {
        jdbc.sql("DELETE FROM skill_share WHERE company_id = ? AND skill_id = ?").params(companyId, skillId).update();
        jdbc.sql("DELETE FROM skill_member_share WHERE company_id = ? AND skill_id = ?")
            .params(companyId, skillId)
            .update();
    }

    // ---- rows --------------------------------------------------------------------------------------------------

    private static SkillRow row(ResultSet rs, int rowNum) throws SQLException {
        return new SkillRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name"), rs.getObject("owner_principal_id", UUID.class), rs.getInt("latest_version"),
                nullableInstant(rs, "deleted_at"), instant(rs, "created_at"), instant(rs, "updated_at"),
                rs.getObject("created_by", UUID.class));
    }

    private static VersionRow version(ResultSet rs, int rowNum) throws SQLException {
        List<SkillBundle.FileEntry> files = new ArrayList<>(JSON.readValue(rs.getString("files"),
                new TypeReference<List<SkillBundle.FileEntry>>() {
                }));
        return new VersionRow(rs.getObject("skill_id", UUID.class), rs.getInt("version"), rs.getString("name"),
                rs.getString("description"), rs.getString("sha256"), rs.getInt("size_bytes"), rs.getInt("file_count"),
                files, rs.getString("note"), instant(rs, "created_at"), rs.getObject("created_by", UUID.class));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
