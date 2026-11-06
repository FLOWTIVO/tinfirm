package com.flowtivo.tinfirm.agent.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.shared.sql.Constraints;

/**
 * SQL access to {@code agent_skill} and {@code agent_settings_skill} (V040), under row-level security: an agent's
 * skills at the versions it pins, and the skills a person's or a trigger's settings switch off (the skills contract,
 * §3.4, §3.5).
 */
@Repository
class AgentSkillStore {

    /** The foreign key that keeps a switch a narrowing: a skill the agent does not have is refused (I4). */
    static final String SWITCH_KEY = "agent_settings_skill_skill_fk";

    private final JdbcClient jdbc;

    AgentSkillStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A skill of an agent at the version it pins. */
    record Pin(UUID skillId, int version) {
    }

    /** The agent's pins. */
    List<Pin> pins(UUID companyId, UUID agentId) {
        return jdbc.sql("SELECT skill_id, version FROM agent_skill WHERE company_id = ? AND agent_id = ?"
                + " ORDER BY skill_id")
            .params(companyId, agentId)
            .query((rs, n) -> new Pin(rs.getObject("skill_id", UUID.class), rs.getInt("version")))
            .list();
    }

    /** The pins of each agent; an agent without skills is absent. */
    Map<UUID, List<Pin>> pins(UUID companyId, Collection<UUID> agentIds) {
        if (agentIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, List<Pin>> pins = new HashMap<>();
        jdbc.sql("SELECT agent_id, skill_id, version FROM agent_skill WHERE company_id = :companyId"
                + " AND agent_id IN (:ids) ORDER BY agent_id, skill_id")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(agentIds))
            .query(rs -> {
                pins.computeIfAbsent(rs.getObject("agent_id", UUID.class), id -> new java.util.ArrayList<>())
                    .add(new Pin(rs.getObject("skill_id", UUID.class), rs.getInt("version")));
            });
        return pins;
    }

    void insert(UUID companyId, UUID agentId, Pin pin, @Nullable UUID by, Instant at) {
        jdbc.sql("""
                INSERT INTO agent_skill (company_id, agent_id, skill_id, version, added_at, added_by, changed_at,
                                         changed_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""")
            .params(companyId, agentId, pin.skillId(), pin.version(), ts(at), by, ts(at), by)
            .update();
    }

    void move(UUID companyId, UUID agentId, Pin pin, @Nullable UUID by, Instant at) {
        jdbc.sql("UPDATE agent_skill SET version = ?, changed_at = ?, changed_by = ? WHERE company_id = ?"
                + " AND agent_id = ? AND skill_id = ?")
            .params(pin.version(), ts(at), by, companyId, agentId, pin.skillId())
            .update();
    }

    /** Takes skills from the agent; every settings switch of them goes with them (the foreign key's cascade). */
    void remove(UUID companyId, UUID agentId, Collection<UUID> skillIds) {
        if (skillIds.isEmpty()) {
            return;
        }
        jdbc.sql("DELETE FROM agent_skill WHERE company_id = :companyId AND agent_id = :agentId"
                + " AND skill_id IN (:ids)")
            .param("companyId", companyId)
            .param("agentId", agentId)
            .param("ids", Set.copyOf(skillIds))
            .update();
    }

    /** The live agents that pin a version of the skill, in the order of their ids (the lock order). */
    List<UUID> liveAgentsPinning(UUID companyId, UUID skillId) {
        return jdbc.sql("""
                SELECT s.agent_id FROM agent_skill s
                  JOIN agent a ON a.id = s.agent_id AND a.deleted_at IS NULL
                 WHERE s.company_id = ? AND s.skill_id = ?
                 ORDER BY s.agent_id""")
            .params(companyId, skillId)
            .query((rs, n) -> rs.getObject("agent_id", UUID.class))
            .list();
    }

    /** The live agents that pin a version of each skill; a skill no live agent pins is absent. */
    Map<UUID, Integer> agentCounts(UUID companyId, Collection<UUID> skillIds) {
        if (skillIds.isEmpty()) {
            return Map.of();
        }
        Map<UUID, Integer> counts = new HashMap<>();
        jdbc.sql("""
                SELECT s.skill_id, count(*) AS agents FROM agent_skill s
                  JOIN agent a ON a.id = s.agent_id AND a.deleted_at IS NULL
                 WHERE s.company_id = :companyId AND s.skill_id IN (:ids)
                 GROUP BY s.skill_id""")
            .param("companyId", companyId)
            .param("ids", Set.copyOf(skillIds))
            .query(rs -> {
                counts.put(rs.getObject("skill_id", UUID.class), rs.getInt("agents"));
            });
        return counts;
    }

    // ---- settings ----------------------------------------------------------------------------------------------

    /** The skills a settings row switches off. */
    Set<UUID> skillsOff(UUID companyId, UUID settingsId) {
        return new LinkedHashSet<>(jdbc.sql("SELECT skill_id FROM agent_settings_skill WHERE company_id = ?"
                + " AND settings_id = ? ORDER BY skill_id")
            .params(companyId, settingsId)
            .query((rs, n) -> rs.getObject("skill_id", UUID.class))
            .list());
    }

    /** Replaces the skills a settings row switches off; the database refuses one the agent does not have. */
    void replaceSkillsOff(UUID companyId, UUID settingsId, UUID agentId, Collection<UUID> skillIds) {
        jdbc.sql("DELETE FROM agent_settings_skill WHERE company_id = ? AND settings_id = ?")
            .params(companyId, settingsId)
            .update();
        for (UUID skillId : skillIds) {
            jdbc.sql("""
                    INSERT INTO agent_settings_skill (company_id, settings_id, agent_id, skill_id)
                    VALUES (?, ?, ?, ?)""")
                .params(companyId, settingsId, agentId, skillId)
                .update();
        }
    }

    /** Whether the database refused a switch of a skill the agent does not have (I4). */
    static boolean widens(DataAccessException ex) {
        return Constraints.violated(ex, SWITCH_KEY);
    }

    private static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
