package com.flowtivo.tinfirm.agent;

import java.util.List;

/**
 * The agent a person's (or, from package 4, a trigger's) turns use (the package 3 contract, §11): the current version
 * narrowed by the version they accepted. Its name, handle, description and runtimes follow the current version; its
 * ceiling is the narrowed one. Its instructions and skills ({@link #content}) are the current version's, or the
 * accepted version's while a change of them waits for acceptance (the security review of 2026-10-27, D1; the owner's
 * decision Q4), less the skills the current version no longer has.
 *
 * @param agent           the agent as of now (the current version)
 * @param acceptedVersion the version they accepted
 * @param definition      the current ceiling narrowed by the accepted one
 * @param pending         what the current version adds over the accepted one, which waits for their acceptance
 * @param contentVersion  the version whose instructions and skills the turns use
 * @param content         those instructions and skills
 */
public record EffectiveAgent(AgentInfo agent, int acceptedVersion, AgentDefinition definition,
        AgentDefinition.Widening pending, int contentVersion, AgentContent content) {

    /** The current version's instructions, without skills. */
    public EffectiveAgent(AgentInfo agent, int acceptedVersion, AgentDefinition definition,
            AgentDefinition.Widening pending) {
        this(agent, acceptedVersion, definition, pending, agent.version(),
                new AgentContent(agent.instructions(), List.of()));
    }

    /** Whether the current version widens the accepted one. */
    public boolean wideningPending() {
        return !pending.none();
    }

    /** The instructions the turns use. */
    public String instructions() {
        return content.instructions();
    }
}
