package com.flowtivo.tinfirm.agent;

import java.util.UUID;

/**
 * A person's, or a trigger's, settings of an agent in effect (the package 3 contract, §5.2 and §11; the package 4
 * contract, §3.3), for conversations, which ask at their start and at every turn. Runs in the caller's transaction and tenant context. Who may use the agent is the
 * caller's check ({@link Agents#access}).
 */
public interface Settings {

    /**
     * {@code Settings.effective(agent, owner)}: the stored row, or the defaults, over the agent as the owner's turns use
     * it (the current version narrowed by the one they accepted; the current one when nothing is stored). A change of
     * the settings applies from each conversation's next turn, except the runtime and the model access, which a
     * conversation fixes at its start.
     *
     * For {@link SettingsOwner.Trigger} (package 4, §3.3): the trigger's stored row, made with the trigger, over the
     * agent as its author accepted it ({@link TriggerSettings}).
     *
     * @throws IllegalArgumentException a deleted or unknown agent, or a trigger without settings of the agent
     */
    EffectiveSettings effective(UUID companyId, UUID agentId, SettingsOwner owner);

    /**
     * The person's first use of the agent (their first conversation with it, contract §3.7): makes their settings row,
     * with the defaults and the current version accepted, unless they have one; then {@link #effective}.
     *
     * @throws IllegalArgumentException a deleted or unknown agent, or a principal that is not a current member's
     */
    EffectiveSettings firstUse(UUID companyId, UUID agentId, UUID principalId);
}
