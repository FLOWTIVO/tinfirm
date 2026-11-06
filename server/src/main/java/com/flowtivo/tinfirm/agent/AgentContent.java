package com.flowtivo.tinfirm.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * What an agent tells its turns as of one version (the security review of 2026-10-27, D1; the owner's decision Q4):
 * its instructions and the skills it pins, each at its version. A change of it widens when the ceiling after the
 * change can act ({@link AgentDefinition#canAct}); otherwise it reaches everyone's next turn, announced.
 *
 * @param instructions the agent's instructions
 * @param skills       its skills, each at the version it pins, by skill id
 */
public record AgentContent(String instructions, List<Pin> skills) {

    public static final AgentContent EMPTY = new AgentContent("", List.of());

    public AgentContent {
        Objects.requireNonNull(instructions, "instructions");
        skills = skills.stream().sorted(Comparator.comparing(Pin::skillId)).toList();
    }

    /** A skill of the agent at the version it pins. */
    public record Pin(UUID skillId, int version) {
    }

    /**
     * The skills this content adds over {@code accepted}, or pins at another version: what a person accepts. A skill
     * this content no longer has narrows and is not among them.
     */
    public List<AgentDefinition.Widening.Skill> skillsOver(AgentContent accepted) {
        Map<UUID, Pin> was = accepted.skills.stream().collect(Collectors.toMap(Pin::skillId, Function.identity()));
        List<AgentDefinition.Widening.Skill> changed = new ArrayList<>();
        for (Pin pin : skills) {
            Pin before = was.get(pin.skillId());
            if (before == null || before.version() != pin.version()) {
                changed.add(new AgentDefinition.Widening.Skill(pin.skillId(), pin.version(),
                        before != null ? before.version() : null));
            }
        }
        return changed;
    }

    /**
     * This content with only the skills {@code current} still has: an accepted version's content as a person's turns
     * use it while the current one waits for their acceptance, where a skill taken from the agent has gone for
     * everyone.
     */
    public AgentContent within(AgentContent current) {
        Set<UUID> kept = current.skills.stream().map(Pin::skillId).collect(Collectors.toSet());
        return new AgentContent(instructions, skills.stream().filter(pin -> kept.contains(pin.skillId())).toList());
    }
}
