package com.flowtivo.tinfirm.agent.internal;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentDefinition;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.skill.SkillInfo;
import com.flowtivo.tinfirm.skill.SkillUsage;
import com.flowtivo.tinfirm.skill.Skills;

/**
 * {@link SkillUsage}: the live agents that pin each skill, and a deleted skill taken from all of them when its
 * deleter asks (the skills contract, §4.5). Taking a skill from an agent narrows it, so it reaches every person's next
 * turn without their acceptance, whoever edits the agent; each change is a version of the agent that widens nothing
 * ({@link AgentVersions#next}), audited with its cause.
 */
@Component
class AgentSkillUsage implements SkillUsage {

    static final String CAUSE = "skill.deleted";

    private final AgentStore agents;

    private final AgentSkillStore pins;

    private final AgentVersions versions;

    private final Skills skills;

    private final AuditLog auditLog;

    private final TimeSource time;

    AgentSkillUsage(AgentStore agents, AgentSkillStore pins, AgentVersions versions, Skills skills,
            AuditLog auditLog, TimeSource time) {
        this.agents = agents;
        this.pins = pins;
        this.versions = versions;
        this.skills = skills;
        this.auditLog = auditLog;
        this.time = time;
    }

    @Override
    @Transactional(readOnly = true)
    public Map<UUID, Integer> agentCounts(UUID companyId, Collection<UUID> skillIds) {
        return pins.agentCounts(companyId, skillIds);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int removeFromAgents(UUID companyId, UUID skillId) {
        String name = skills.find(companyId, skillId).map(SkillInfo::name).orElse("");
        int removed = 0;
        for (UUID agentId : pins.liveAgentsPinning(companyId, skillId)) {
            AgentStore.AgentRow row = agents.lockLive(companyId, agentId).orElse(null);
            Integer version = row == null ? null : pins.pins(companyId, agentId)
                .stream()
                .filter(pin -> pin.skillId().equals(skillId))
                .map(AgentSkillStore.Pin::version)
                .findFirst()
                .orElse(null);
            if (version == null) {
                // Deleted, or the pin went meanwhile.
                continue;
            }
            AgentDefinition before = agents.definition(companyId, agentId);
            Instant now = time.now();
            pins.remove(companyId, agentId, List.of(skillId));
            AgentVersions.Versioned versioned = versions.next(row, before, row.name(), row.handle(), row.runtimes(),
                    null, now);
            AgentSkills.Change change = new AgentSkills.Change(List.of(),
                    List.of(new AgentSkills.Entry(skillId, name, version)), List.of());
            AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.AGENT_SKILLS_CHANGED,
                    CurrentActor.actor())
                .target(AgentVersions.TARGET, agentId)
                .detail("version", versioned.version())
                .detail("cause", CAUSE);
            change.details().forEach(audit::detail);
            auditLog.record(audit.build());
            // Nobody edited the agent: no editor's settings follow; the narrowing reaches everyone at once.
            versions.announce(companyId, agentId, row.version(), versioned, null);
            removed++;
        }
        return removed;
    }
}
