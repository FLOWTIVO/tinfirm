package com.flowtivo.tinfirm.skill;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * The port through which the skill module reaches the agents that pin its skills (the skills contract, §4.5, §6's
 * {@code agentCount} and §10's {@code skill.deleted}); implemented by the agent module, which keeps the pins. Called in
 * the caller's transaction.
 */
public interface SkillUsage {

    /** The live agents that pin a version of each skill; a skill no live agent pins is absent. */
    Map<UUID, Integer> agentCounts(UUID companyId, Collection<UUID> skillIds);

    /**
     * Takes the skill from every live agent that pins it, as its deleter asked ({@code removeFromAgents}): each agent
     * locked in the order of its id, its change a version that widens nothing, audited as {@code agent.skills_changed}
     * with {@code cause} {@code skill.deleted} and announced. The skill's row is locked by the caller {@code FOR UPDATE},
     * so no change of an agent's pins that names it is under way ({@link Skills#lockForPins}). Answers how many agents
     * lost it.
     */
    int removeFromAgents(UUID companyId, UUID skillId);
}
