package com.flowtivo.tinfirm.agent.internal;

import java.sql.Array;
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

import com.flowtivo.tinfirm.agent.AgentContent;
import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.AgentStatus;
import com.flowtivo.tinfirm.agent.RepositoryAccess;
import com.flowtivo.tinfirm.agent.ToolMode;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.system.ToolEffect;

/**
 * SQL access to {@code agent}, {@code agent_version}, {@code agent_system}, {@code agent_tool},
 * {@code agent_repository}, {@code agent_host}, {@code agent_share} (V003) and {@code agent_member_share} (V034), under
 * row-level security: the agents of the package 3 contract, §3.1 to §3.6 and §11.
 */
@Repository
class AgentStore {

    private static final String COLUMNS = "id, company_id, name, handle, description, instructions, runtimes,"
            + " turn_time_limit_minutes, memory_allowed, owner_principal_id, status, paused_at, paused_by, version,"
            + " deleted_at, created_at, updated_at, created_by";

    private final JdbcClient jdbc;

    AgentStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * An agent row.
     *
     * @param turnTimeLimitMinutes how long one of its turns may run; null for the deployment's
     *                             {@code TINFIRM_RUN_MAX_WALL_CLOCK}
     * @param memoryAllowed        whether its people and triggers may turn its memory on (ADR-0063)
     */
    record AgentRow(UUID id, UUID companyId, String name, String handle, @Nullable String description,
            String instructions, List<RuntimeKind> runtimes, @Nullable Integer turnTimeLimitMinutes,
            boolean memoryAllowed, @Nullable UUID ownerPrincipalId, AgentStatus status,
            @Nullable Instant pausedAt, @Nullable UUID pausedBy, int version, @Nullable Instant deletedAt,
            Instant createdAt, Instant updatedAt, @Nullable UUID createdBy) {

        AgentRow {
            runtimes = List.copyOf(runtimes);
        }

        AgentInfo info() {
            return new AgentInfo(id, companyId, name, handle, description, instructions, runtimes,
                    turnTimeLimitMinutes, memoryAllowed, status, ownerPrincipalId, version, deletedAt);
        }
    }

    /** A network host of an agent, with who added it and why. */
    record HostRow(UUID id, String host, @Nullable String reason, @Nullable UUID addedBy, @Nullable UUID decisionId,
            Instant addedAt) {
    }

    /** Which agents a listing shows. */
    enum Scope {
        /** Every live agent (OWNER, ADMIN). */
        ALL,
        /** The caller's own, those shared with their groups and those shared with them. */
        USABLE
    }

    /**
     * Whom an agent is shared with: the groups whose members use it, and the members who use it on their own (by
     * principal).
     */
    record Shares(Set<UUID> groupIds, Set<UUID> principalIds) {

        static final Shares NONE = new Shares(Set.of(), Set.of());

        Shares {
            groupIds = Set.copyOf(groupIds);
            principalIds = Set.copyOf(principalIds);
        }

        boolean isEmpty() {
            return groupIds.isEmpty() && principalIds.isEmpty();
        }
    }

    // ---- agents ----------------------------------------------------------------------------------------------------

    void insert(AgentRow row) {
        jdbc.sql("""
                INSERT INTO agent (id, company_id, name, handle, description, instructions, runtimes,
                                   turn_time_limit_minutes, memory_allowed, owner_principal_id, status, version, created_at,
                                   updated_at, created_by)
                VALUES (?, ?, ?, ?, ?, ?, CAST(? AS varchar(16)[]), ?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(row.id(), row.companyId(), row.name(), row.handle(), row.description(), row.instructions(),
                    names(row.runtimes()), row.turnTimeLimitMinutes(), row.memoryAllowed(), row.ownerPrincipalId(),
                    row.status().name(),
                    row.version(), ts(row.createdAt()), ts(row.updatedAt()), row.createdBy())
            .update();
    }

    /** The agent, live or deleted. */
    Optional<AgentRow> find(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent WHERE company_id = ? AND id = ?")
            .params(companyId, id)
            .query(AgentStore::row)
            .optional();
    }

    Optional<AgentRow> findLive(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent WHERE company_id = ? AND id = ? AND deleted_at IS NULL")
            .params(companyId, id)
            .query(AgentStore::row)
            .optional();
    }

    /**
     * The live agent, locked: every change of an agent and of its versions goes one at a time. {@code FOR NO KEY
     * UPDATE}, as every lock of this table: the foreign keys' checks of the rows that name an agent (a run, a
     * conversation, a conversation's row written again in its transaction) never wait on it, so that pausing an agent,
     * which then locks its conversations (CONVENTIONS §10), never waits on a turn that waits on the agent. No agent's
     * id changes and none is deleted.
     */
    Optional<AgentRow> lockLive(UUID companyId, UUID id) {
        return jdbc.sql("SELECT " + COLUMNS
                + " FROM agent WHERE company_id = ? AND id = ? AND deleted_at IS NULL FOR NO KEY UPDATE")
            .params(companyId, id)
            .query(AgentStore::row)
            .optional();
    }

    /**
     * Live agents ordered by (name, id), after the given position: every one ({@link Scope#ALL}), or those the
     * principal owns, one of the groups is given or the principal is given ({@link Scope#USABLE}).
     */
    List<AgentRow> page(UUID companyId, Scope scope, @Nullable UUID principalId, Collection<UUID> groupIds,
            @Nullable String afterName, @Nullable UUID afterId, int limit) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("companyId", companyId);
        StringBuilder sql = new StringBuilder("SELECT " + COLUMNS
                + " FROM agent a WHERE a.company_id = :companyId AND a.deleted_at IS NULL");
        if (scope == Scope.USABLE) {
            sql.append(" AND (a.owner_principal_id = :principalId");
            params.put("principalId", principalId);
            if (principalId != null) {
                sql.append(" OR EXISTS (SELECT 1 FROM agent_member_share m WHERE m.agent_id = a.id"
                        + " AND m.principal_id = :principalId)");
            }
            if (!groupIds.isEmpty()) {
                sql.append(" OR EXISTS (SELECT 1 FROM agent_share s WHERE s.agent_id = a.id"
                        + " AND s.group_id IN (:groupIds))");
                params.put("groupIds", Set.copyOf(groupIds));
            }
            sql.append(")");
        }
        if (afterName != null && afterId != null) {
            sql.append(" AND (a.name, a.id) > (:afterName, :afterId)");
            params.put("afterName", afterName);
            params.put("afterId", afterId);
        }
        sql.append(" ORDER BY a.name, a.id LIMIT :limit");
        params.put("limit", limit);
        return jdbc.sql(sql.toString()).params(params).query(AgentStore::row).list();
    }

    boolean nameTaken(UUID companyId, String name, @Nullable UUID exceptId) {
        return jdbc.sql("""
                SELECT count(*) FROM agent
                 WHERE company_id = ? AND deleted_at IS NULL AND lower(name) = lower(?) AND id IS DISTINCT FROM ?""")
            .params(companyId, name, exceptId)
            .query(Long.class)
            .single() > 0;
    }

    /** The live agent with the handle. */
    Optional<AgentRow> findLiveByHandle(UUID companyId, String handle) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent WHERE company_id = ? AND deleted_at IS NULL AND handle = ?")
            .params(companyId, handle)
            .query(AgentStore::row)
            .optional();
    }

    /** The handles of other live agents that start with {@code prefix}: the candidates a new handle must avoid. */
    Set<String> handlesLike(UUID companyId, String prefix, @Nullable UUID exceptId) {
        return new HashSet<>(jdbc.sql("""
                SELECT handle FROM agent
                 WHERE company_id = ? AND deleted_at IS NULL AND left(handle, ?) = ? AND id IS DISTINCT FROM ?""")
            .params(companyId, prefix.length(), prefix, exceptId)
            .query(String.class)
            .list());
    }

    void update(UUID companyId, UUID id, String name, String handle, @Nullable String description,
            String instructions, List<RuntimeKind> runtimes, @Nullable Integer turnTimeLimitMinutes,
            boolean memoryAllowed, Instant at) {
        jdbc.sql("""
                UPDATE agent SET name = ?, handle = ?, description = ?, instructions = ?,
                                 runtimes = CAST(? AS varchar(16)[]), turn_time_limit_minutes = ?, memory_allowed = ?,
                                 updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(name, handle, description, instructions, names(runtimes), turnTimeLimitMinutes, memoryAllowed, ts(at),
                    companyId, id)
            .update();
    }

    void setVersion(UUID companyId, UUID id, int version, Instant at) {
        jdbc.sql("UPDATE agent SET version = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(version, ts(at), companyId, id)
            .update();
    }

    void pause(UUID companyId, UUID id, @Nullable UUID by, Instant at) {
        jdbc.sql("""
                UPDATE agent SET status = 'PAUSED', paused_at = ?, paused_by = ?, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(ts(at), by, ts(at), companyId, id)
            .update();
    }

    void resume(UUID companyId, UUID id, Instant at) {
        jdbc.sql("""
                UPDATE agent SET status = 'ACTIVE', paused_at = NULL, paused_by = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(ts(at), companyId, id)
            .update();
    }

    void softDelete(UUID companyId, UUID id, @Nullable UUID by, Instant at) {
        jdbc.sql("UPDATE agent SET deleted_at = ?, deleted_by = ?, updated_at = ? WHERE company_id = ? AND id = ?")
            .params(ts(at), by, ts(at), companyId, id)
            .update();
    }

    /** The live agents the principal owns, locked. */
    List<AgentRow> lockOwnedBy(UUID companyId, UUID principalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent WHERE company_id = ? AND owner_principal_id = ?"
                + " AND deleted_at IS NULL ORDER BY id FOR NO KEY UPDATE")
            .params(companyId, principalId)
            .query(AgentStore::row)
            .list();
    }

    /** Every agent of the principal passes to the admins, deleted ones included. */
    void clearOwner(UUID companyId, UUID principalId, Instant at) {
        jdbc.sql("""
                UPDATE agent SET owner_principal_id = NULL, former_owner_principal_id = owner_principal_id,
                       updated_at = ?
                 WHERE company_id = ? AND owner_principal_id = ?""")
            .params(ts(at), companyId, principalId)
            .update();
    }

    /** Gives the agent a new owner (package 6, open point 13); the former owner's mark goes. */
    void setOwner(UUID companyId, UUID id, UUID ownerPrincipalId, Instant at) {
        jdbc.sql("""
                UPDATE agent SET owner_principal_id = ?, former_owner_principal_id = NULL, updated_at = ?
                 WHERE company_id = ? AND id = ?""")
            .params(ownerPrincipalId, ts(at), companyId, id)
            .update();
    }

    /** The live agents passed to the admins from the principal, by id with their names. */
    Map<UUID, String> passedFrom(UUID companyId, UUID principalId) {
        Map<UUID, String> agents = new java.util.LinkedHashMap<>();
        jdbc.sql("""
                SELECT id, name FROM agent
                 WHERE company_id = ? AND former_owner_principal_id = ? AND owner_principal_id IS NULL
                   AND deleted_at IS NULL
                 ORDER BY name, id""")
            .params(companyId, principalId)
            .query(rs -> {
                agents.put(rs.getObject("id", UUID.class), rs.getString("name"));
            });
        return agents;
    }

    List<com.flowtivo.tinfirm.agent.Agents.PausedAgent> paused(UUID companyId) {
        return jdbc.sql("SELECT id, name, paused_at, paused_by FROM agent WHERE company_id = ? AND status = 'PAUSED'"
                + " AND deleted_at IS NULL ORDER BY paused_at DESC, id")
            .param(companyId)
            .query((rs, row) -> new com.flowtivo.tinfirm.agent.Agents.PausedAgent(rs.getObject("id", UUID.class),
                    rs.getString("name"), rs.getObject("paused_at", java.time.OffsetDateTime.class).toInstant(),
                    rs.getObject("paused_by", UUID.class)))
            .list();
    }

    int countOwnedBy(UUID companyId, UUID principalId) {
        return jdbc.sql("SELECT count(*) FROM agent WHERE company_id = ? AND owner_principal_id = ?"
                + " AND deleted_at IS NULL")
            .params(companyId, principalId)
            .query(Integer.class)
            .single();
    }

    /** The live agents shared with the group, by (name, id): their ids and names, in one query. */
    List<Map.Entry<UUID, String>> namesSharedWith(UUID companyId, UUID groupId) {
        return jdbc.sql("""
                SELECT a.id, a.name FROM agent a
                  JOIN agent_share s ON s.agent_id = a.id AND s.company_id = a.company_id
                 WHERE s.company_id = ? AND s.group_id = ? AND a.deleted_at IS NULL
                 ORDER BY a.name, a.id""")
            .params(companyId, groupId)
            .query((rs, row) -> Map.entry(rs.getObject("id", UUID.class), rs.getString("name")))
            .list();
    }

    /** The names of the agents, deleted ones included. */
    Map<UUID, String> names(Collection<UUID> ids) {
        Map<UUID, String> names = new HashMap<>();
        if (ids.isEmpty()) {
            return names;
        }
        jdbc.sql("SELECT id, name FROM agent WHERE id IN (:ids)")
            .param("ids", Set.copyOf(ids))
            .query(rs -> {
                names.put(rs.getObject("id", UUID.class), rs.getString("name"));
            });
        return names;
    }

    // ---- versions --------------------------------------------------------------------------------------------------

    /**
     * Writes a version.
     *
     * @param definition   its name, handle, runtimes and ceiling ({@link AgentJson#version})
     * @param instructions its instructions
     * @param skills       its pins ({@link AgentJson#skills})
     */
    void insertVersion(UUID companyId, UUID agentId, int version, String definition, String instructions,
            String skills, boolean widens, @Nullable UUID decisionId, Instant at, @Nullable UUID by) {
        jdbc.sql("""
                INSERT INTO agent_version (company_id, agent_id, version, definition, instructions, skills, widens,
                                           decision_id, created_at, created_by)
                VALUES (?, ?, ?, CAST(? AS jsonb), ?, CAST(? AS jsonb), ?, ?, ?, ?)""")
            .params(companyId, agentId, version, definition, instructions, skills, widens, decisionId, ts(at), by)
            .update();
    }

    /** The definition of one version as stored, JSON. */
    Optional<String> versionDefinition(UUID companyId, UUID agentId, int version) {
        return jdbc.sql("""
                SELECT definition::text FROM agent_version WHERE company_id = ? AND agent_id = ? AND version = ?""")
            .params(companyId, agentId, version)
            .query(String.class)
            .optional();
    }

    /** One version as stored: its ceiling and what it tells its turns (V080). */
    record StoredVersion(AgentDefinition definition, AgentContent content) {
    }

    Optional<StoredVersion> version(UUID companyId, UUID agentId, int version) {
        return jdbc.sql("""
                SELECT definition::text AS definition, instructions, skills::text AS skills
                  FROM agent_version WHERE company_id = ? AND agent_id = ? AND version = ?""")
            .params(companyId, agentId, version)
            .query((rs, n) -> new StoredVersion(AgentJson.definition(rs.getString("definition")),
                    new AgentContent(rs.getString("instructions"), AgentJson.skills(rs.getString("skills")))))
            .optional();
    }

    /**
     * Versions {@code from} to {@code to} of the agent, oldest first, by what they tell the turns: a digest of the
     * instructions and the pins, without the text.
     */
    record ContentRow(int version, String instructionsDigest, String skills, Instant createdAt,
            @Nullable UUID createdBy) {
    }

    List<ContentRow> contentRows(UUID companyId, UUID agentId, int from, int to) {
        return jdbc.sql("""
                SELECT version, md5(instructions) AS digest, skills::text AS skills, created_at, created_by
                  FROM agent_version
                 WHERE company_id = ? AND agent_id = ? AND version BETWEEN ? AND ?
                 ORDER BY version""")
            .params(companyId, agentId, from, to)
            .query((rs, n) -> new ContentRow(rs.getInt("version"), rs.getString("digest"), rs.getString("skills"),
                    instant(rs, "created_at"), rs.getObject("created_by", UUID.class)))
            .list();
    }

    /** The agent's instructions as its row holds them now. */
    String instructions(UUID companyId, UUID agentId) {
        return jdbc.sql("SELECT instructions FROM agent WHERE company_id = ? AND id = ?")
            .params(companyId, agentId)
            .query(String.class)
            .single();
    }

    // ---- the ceiling -----------------------------------------------------------------------------------------------

    /** The agent's current definition, as its tables hold it. */
    AgentDefinition definition(UUID companyId, UUID agentId) {
        Map<UUID, List<AgentDefinition.ToolEntry>> tools = new LinkedHashMap<>();
        for (UUID systemId : jdbc.sql("""
                SELECT system_id FROM agent_system WHERE company_id = ? AND agent_id = ? ORDER BY system_id""")
            .params(companyId, agentId)
            .query(UUID.class)
            .list()) {
            tools.put(systemId, new ArrayList<>());
        }
        jdbc.sql("""
                SELECT system_id, tool, mode, chosen_for_effect FROM agent_tool
                 WHERE company_id = ? AND agent_id = ? ORDER BY system_id, tool""")
            .params(companyId, agentId)
            .query(rs -> {
                tools.computeIfAbsent(rs.getObject("system_id", UUID.class), id -> new ArrayList<>())
                    .add(new AgentDefinition.ToolEntry(rs.getString("tool"), ToolMode.valueOf(rs.getString("mode")),
                            ToolEffect.valueOf(rs.getString("chosen_for_effect"))));
            });
        List<AgentDefinition.SystemEntry> systems = new ArrayList<>();
        tools.forEach((systemId, entries) -> systems.add(new AgentDefinition.SystemEntry(systemId, entries)));
        List<AgentDefinition.RepositoryEntry> repositories = jdbc.sql("""
                SELECT repository_id, system_id, access FROM agent_repository
                 WHERE company_id = ? AND agent_id = ? ORDER BY repository_id""")
            .params(companyId, agentId)
            .query((rs, n) -> new AgentDefinition.RepositoryEntry(rs.getObject("repository_id", UUID.class),
                    rs.getObject("system_id", UUID.class), RepositoryAccess.valueOf(rs.getString("access"))))
            .list();
        List<String> hosts = jdbc.sql("SELECT host FROM agent_host WHERE company_id = ? AND agent_id = ? ORDER BY host")
            .params(companyId, agentId)
            .query(String.class)
            .list();
        return new AgentDefinition(systems, repositories, hosts);
    }

    void insertSystem(UUID companyId, UUID agentId, UUID systemId) {
        jdbc.sql("INSERT INTO agent_system (company_id, agent_id, system_id) VALUES (?, ?, ?)")
            .params(companyId, agentId, systemId)
            .update();
    }

    /** Removes systems from the agent; their tools and repositories go with them (ON DELETE CASCADE). */
    void deleteSystems(UUID companyId, UUID agentId, Collection<UUID> systemIds) {
        if (systemIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM agent_system WHERE company_id = :companyId AND agent_id = :agentId"
                + " AND system_id IN (:systemIds)")
            .param("companyId", companyId)
            .param("agentId", agentId)
            .param("systemIds", Set.copyOf(systemIds))
            .update();
    }

    void deleteTools(UUID companyId, UUID agentId) {
        jdbc.sql("DELETE FROM agent_tool WHERE company_id = ? AND agent_id = ?").params(companyId, agentId).update();
    }

    void insertTool(UUID companyId, UUID agentId, UUID systemId, AgentDefinition.ToolEntry tool) {
        jdbc.sql("""
                INSERT INTO agent_tool (company_id, agent_id, system_id, tool, mode, chosen_for_effect)
                VALUES (?, ?, ?, ?, ?, ?)""")
            .params(companyId, agentId, systemId, tool.name(), tool.mode().name(), tool.chosenForEffect().name())
            .update();
    }

    void deleteRepositories(UUID companyId, UUID agentId) {
        jdbc.sql("DELETE FROM agent_repository WHERE company_id = ? AND agent_id = ?")
            .params(companyId, agentId)
            .update();
    }

    void insertRepository(UUID companyId, UUID agentId, AgentDefinition.RepositoryEntry repository) {
        jdbc.sql("""
                INSERT INTO agent_repository (company_id, agent_id, system_id, repository_id, access)
                VALUES (?, ?, ?, ?, ?)""")
            .params(companyId, agentId, repository.systemId(), repository.repositoryId(), repository.access().name())
            .update();
    }

    List<HostRow> hosts(UUID companyId, UUID agentId) {
        return jdbc.sql("""
                SELECT id, host, reason, added_by, decision_id, added_at FROM agent_host
                 WHERE company_id = ? AND agent_id = ? ORDER BY host""")
            .params(companyId, agentId)
            .query((rs, n) -> new HostRow(rs.getObject("id", UUID.class), rs.getString("host"),
                    rs.getString("reason"), rs.getObject("added_by", UUID.class),
                    rs.getObject("decision_id", UUID.class), instant(rs, "added_at")))
            .list();
    }

    void insertHost(UUID companyId, UUID agentId, HostRow host) {
        jdbc.sql("""
                INSERT INTO agent_host (id, company_id, agent_id, host, reason, added_by, decision_id, added_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(host.id(), companyId, agentId, host.host(), host.reason(), host.addedBy(), host.decisionId(),
                    ts(host.addedAt()))
            .update();
    }

    void updateHostReason(UUID companyId, UUID hostId, @Nullable String reason) {
        jdbc.sql("UPDATE agent_host SET reason = ? WHERE company_id = ? AND id = ?")
            .params(reason, companyId, hostId)
            .update();
    }

    void deleteHosts(UUID companyId, Collection<UUID> hostIds) {
        if (hostIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM agent_host WHERE company_id = :companyId AND id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(hostIds))
            .update();
    }

    // ---- shares ----------------------------------------------------------------------------------------------------

    Shares shares(UUID companyId, UUID agentId) {
        return shares(companyId, List.of(agentId)).getOrDefault(agentId, Shares.NONE);
    }

    /** Whom each agent is shared with; an agent shared with nobody is absent. */
    Map<UUID, Shares> shares(UUID companyId, Collection<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Set<UUID>> groups = new HashMap<>();
        Map<UUID, Set<UUID>> members = new HashMap<>();
        jdbc.sql("SELECT agent_id, group_id FROM agent_share WHERE company_id = :companyId AND agent_id IN (:ids)")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(agentIds))
            .query(rs -> {
                groups.computeIfAbsent(rs.getObject("agent_id", UUID.class), id -> new HashSet<>())
                    .add(rs.getObject("group_id", UUID.class));
            });
        // A member who left uses nothing. Their rows go when they leave; a row that a share saved during the leaving
        // wrote after it looked (nothing locks the principal's row against it) is ignored.
        jdbc.sql("""
                SELECT m.agent_id, m.principal_id FROM agent_member_share m
                  JOIN principal p ON p.id = m.principal_id AND p.company_id = m.company_id
                 WHERE m.company_id = :companyId AND m.agent_id IN (:ids) AND p.status <> 'LEFT'""")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(agentIds))
            .query(rs -> {
                members.computeIfAbsent(rs.getObject("agent_id", UUID.class), id -> new HashSet<>())
                    .add(rs.getObject("principal_id", UUID.class));
            });
        Map<UUID, Shares> shares = new HashMap<>();
        Set<UUID> shared = new HashSet<>(groups.keySet());
        shared.addAll(members.keySet());
        for (UUID agentId : shared) {
            shares.put(agentId, new Shares(groups.getOrDefault(agentId, Set.of()),
                    members.getOrDefault(agentId, Set.of())));
        }
        return shares;
    }

    void addShares(UUID companyId, UUID agentId, Collection<UUID> groupIds, @Nullable UUID by, Instant at) {
        for (UUID groupId : groupIds) {
            jdbc.sql("""
                    INSERT INTO agent_share (company_id, agent_id, group_id, shared_by, shared_at)
                    VALUES (?, ?, ?, ?, ?)""")
                .params(companyId, agentId, groupId, by, ts(at))
                .update();
        }
    }

    void removeShares(UUID companyId, UUID agentId, Collection<UUID> groupIds) {
        if (groupIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM agent_share WHERE company_id = :companyId AND agent_id = :agentId"
                + " AND group_id IN (:groupIds)")
            .param("companyId", companyId)
            .param("agentId", agentId)
            .param("groupIds", Set.copyOf(groupIds))
            .update();
    }

    void addMemberShares(UUID companyId, UUID agentId, Collection<UUID> principalIds, @Nullable UUID by,
            Instant at) {
        for (UUID principalId : principalIds) {
            jdbc.sql("""
                    INSERT INTO agent_member_share (company_id, agent_id, principal_id, shared_by, shared_at)
                    VALUES (?, ?, ?, ?, ?)""")
                .params(companyId, agentId, principalId, by, ts(at))
                .update();
        }
    }

    void removeMemberShares(UUID companyId, UUID agentId, Collection<UUID> principalIds) {
        if (principalIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM agent_member_share WHERE company_id = :companyId AND agent_id = :agentId"
                + " AND principal_id IN (:principalIds)")
            .param("companyId", companyId)
            .param("agentId", agentId)
            .param("principalIds", Set.copyOf(principalIds))
            .update();
    }

    /** Takes the agent from everyone it is shared with: its groups and its members. */
    void deleteShares(UUID companyId, UUID agentId) {
        jdbc.sql("DELETE FROM agent_share WHERE company_id = ? AND agent_id = ?").params(companyId, agentId).update();
        jdbc.sql("DELETE FROM agent_member_share WHERE company_id = ? AND agent_id = ?")
            .params(companyId, agentId)
            .update();
    }

    /** The live agents shared with the group, locked. */
    List<AgentRow> lockSharedWith(UUID companyId, UUID groupId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent a WHERE a.company_id = ? AND a.deleted_at IS NULL"
                + " AND EXISTS (SELECT 1 FROM agent_share s WHERE s.agent_id = a.id AND s.group_id = ?)"
                + " ORDER BY a.id FOR NO KEY UPDATE")
            .params(companyId, groupId)
            .query(AgentStore::row)
            .list();
    }

    /** The live agents shared with the member's principal on their own, locked. */
    List<AgentRow> lockSharedWithMember(UUID companyId, UUID principalId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM agent a WHERE a.company_id = ? AND a.deleted_at IS NULL"
                + " AND EXISTS (SELECT 1 FROM agent_member_share m WHERE m.agent_id = a.id AND m.principal_id = ?)"
                + " ORDER BY a.id FOR NO KEY UPDATE")
            .params(companyId, principalId)
            .query(AgentStore::row)
            .list();
    }

    // ---- rows ------------------------------------------------------------------------------------------------------

    private static AgentRow row(ResultSet rs, int rowNum) throws SQLException {
        return new AgentRow(rs.getObject("id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getString("name"), rs.getString("handle"), rs.getString("description"),
                rs.getString("instructions"), runtimes(rs.getArray("runtimes")),
                rs.getObject("turn_time_limit_minutes", Integer.class), rs.getBoolean("memory_allowed"),
                rs.getObject("owner_principal_id", UUID.class),
                AgentStatus.valueOf(rs.getString("status")), nullableInstant(rs, "paused_at"),
                rs.getObject("paused_by", UUID.class), rs.getInt("version"), nullableInstant(rs, "deleted_at"),
                instant(rs, "created_at"), instant(rs, "updated_at"), rs.getObject("created_by", UUID.class));
    }

    private static String[] names(List<RuntimeKind> runtimes) {
        return runtimes.stream().map(Enum::name).toArray(String[]::new);
    }

    private static List<RuntimeKind> runtimes(Array array) throws SQLException {
        List<RuntimeKind> runtimes = new ArrayList<>();
        for (Object runtime : (Object[]) array.getArray()) {
            runtimes.add(RuntimeKind.valueOf((String) runtime));
        }
        return runtimes;
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
