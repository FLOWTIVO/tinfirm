package com.flowtivo.tinfirm.company.internal;

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

/**
 * SQL access to {@code user_group} and {@code user_group_member} (V001), under row-level security: the
 * groups of members of ADR-0049 §2.
 */
@Repository
class GroupStore {

    private static final String COLUMNS = "id, company_id, name, description, created_at, updated_at";

    private final JdbcClient jdbc;

    GroupStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A group row; {@code name} is unique in the company ignoring case. */
    record GroupRow(UUID id, UUID companyId, String name, @Nullable String description, Instant createdAt,
            Instant updatedAt) {
    }

    /** A group as {@code listGroups} shows it. */
    record GroupCount(GroupRow group, int memberCount) {
    }

    /** Groups ordered by (name, id), after the given position, with their member counts. */
    List<GroupCount> page(UUID companyId, @Nullable String afterName, @Nullable UUID afterId, int limit) {
        List<Object> params = new ArrayList<>(List.of(companyId));
        StringBuilder sql = new StringBuilder("SELECT g.id, g.company_id, g.name, g.description, g.created_at,"
                + " g.updated_at, (SELECT count(*) FROM user_group_member m WHERE m.group_id = g.id) AS member_count"
                + " FROM user_group g WHERE g.company_id = ?");
        if (afterName != null && afterId != null) {
            sql.append(" AND (g.name, g.id) > (?, ?)");
            params.add(afterName);
            params.add(afterId);
        }
        sql.append(" ORDER BY g.name, g.id LIMIT ?");
        params.add(limit);
        return jdbc.sql(sql.toString())
            .params(params)
            .query((rs, row) -> new GroupCount(group(rs, row), rs.getInt("member_count")))
            .list();
    }

    Optional<GroupRow> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM user_group WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(GroupStore::group)
            .optional();
    }

    Optional<GroupRow> lock(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM user_group WHERE company_id = ? AND id = ? FOR UPDATE")
            .params(companyId, id)
            .query(GroupStore::group)
            .optional();
    }

    boolean nameTaken(UUID companyId, String name, @Nullable UUID exceptId) {
        return jdbc.sql("""
                SELECT count(*) FROM user_group
                 WHERE company_id = ? AND lower(name) = lower(?) AND id IS DISTINCT FROM ?""")
            .params(companyId, name, exceptId)
            .query(Long.class)
            .single() > 0;
    }

    void insert(GroupRow group) {
        jdbc.sql("""
                INSERT INTO user_group (id, company_id, name, description, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)""")
            .params(group.id(), group.companyId(), group.name(), group.description(), ts(group.createdAt()),
                    ts(group.updatedAt()))
            .update();
    }

    void update(GroupRow group) {
        jdbc.sql("UPDATE user_group SET name = ?, description = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(group.name(), group.description(), ts(group.updatedAt()), group.companyId(), group.id())
            .update();
    }

    /** Deletes the group; its members go with it (ON DELETE CASCADE). */
    void delete(UUID companyId, UUID id) {
        jdbc.sql("DELETE FROM user_group WHERE company_id = ? AND id = ?").params(companyId, id).update();
    }

    /** The members of the group, by user id. */
    List<UUID> members(UUID groupId) {
        return jdbc.sql("SELECT user_id FROM user_group_member WHERE group_id = ? ORDER BY user_id")
            .param(groupId)
            .query(UUID.class)
            .list();
    }

    /** Replaces the members of the group as a whole. */
    void replaceMembers(UUID companyId, UUID groupId, Collection<UUID> userIds) {
        jdbc.sql("DELETE FROM user_group_member WHERE company_id = ? AND group_id = ?")
            .params(companyId, groupId)
            .update();
        for (UUID userId : userIds) {
            jdbc.sql("INSERT INTO user_group_member (company_id, group_id, user_id) VALUES (?, ?, ?)")
                .params(companyId, groupId, userId)
                .update();
        }
    }

    /**
     * Adds one member and touches no other row, so that two admins who add different people at the same moment both
     * keep theirs. False when they were in the group already.
     */
    boolean addMember(UUID companyId, UUID groupId, UUID userId) {
        return jdbc.sql("""
                INSERT INTO user_group_member (company_id, group_id, user_id) VALUES (?, ?, ?)
                ON CONFLICT (group_id, user_id) DO NOTHING""")
            .params(companyId, groupId, userId)
            .update() > 0;
    }

    /** Takes one member out and touches no other row; false when they were not in the group. */
    boolean removeMember(UUID companyId, UUID groupId, UUID userId) {
        return jdbc.sql("DELETE FROM user_group_member WHERE company_id = ? AND group_id = ? AND user_id = ?")
            .params(companyId, groupId, userId)
            .update() > 0;
    }

    /** Those of the users who are members of the company. */
    Set<UUID> membersOfCompany(UUID companyId, Collection<UUID> userIds) {
        if (userIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("""
                SELECT user_id FROM company_membership WHERE company_id = :companyId AND user_id IN (:ids)""")
            .param("companyId", companyId)
            .param("ids", userIds)
            .query(UUID.class)
            .list());
    }

    /** The groups the user is a member of in the company. */
    Set<UUID> groupsOf(UUID companyId, UUID userId) {
        return new HashSet<>(jdbc.sql("SELECT group_id FROM user_group_member WHERE company_id = ? AND user_id = ?")
            .params(companyId, userId)
            .query(UUID.class)
            .list());
    }

    /** The names of the company's groups among the ids; other ids are absent. */
    Map<UUID, String> names(UUID companyId, Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, name FROM user_group WHERE company_id = :companyId AND id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", ids)
            .query(rs -> {
                names.put(rs.getObject("id", UUID.class), rs.getString("name"));
            });
        return names;
    }

    private static GroupRow group(ResultSet rs, int row) throws SQLException {
        return new GroupRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name"), rs.getString("description"),
                rs.getObject("created_at", OffsetDateTime.class).toInstant(),
                rs.getObject("updated_at", OffsetDateTime.class).toInstant());
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
