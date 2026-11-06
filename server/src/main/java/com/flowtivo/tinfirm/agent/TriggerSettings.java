package com.flowtivo.tinfirm.agent;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.AgentSettings;
import com.flowtivo.tinfirm.shared.api.model.AgentSettingsRequest;

/**
 * A trigger's settings of its agent (the package 4 contract, §3.3), for the {@code trigger} module, which decides who
 * may read and save them and audits them ({@code trigger.settings_changed}, {@code trigger.accepted}). The row is made
 * with the trigger ({@code agent_settings.trigger_id}) and records the agent's version its author accepted; its turns
 * use the current version narrowed by it, and {@link Settings#effective} answers it for
 * {@link SettingsOwner.Trigger}. Package 3's checks hold, for the trigger: the runtime among the agent's
 * ({@code RUNTIME_NOT_LISTED}), the model among those the payer's model access allows ({@code MODEL_NOT_ALLOWED}), a
 * subscription only while the company allows one for a trigger and the payer is the author, on the author's own personal
 * host ({@code SUBSCRIPTION_NOT_SERVING}; ADR-0085, decision 7), narrowing only, and the author's own MCP servers only while the trigger allows them (T3: every row of its table the
 * author's own account; else {@code SETTINGS_WIDEN}). They add no instructions ({@code VALIDATION_FAILED}): the
 * trigger's own instruction is in every turn. Runs in the caller's transaction and tenant context.
 */
public interface TriggerSettings {

    /**
     * Whose settings they are, for the checks.
     *
     * @param ownServersAllowed   whether every row of the trigger's table holds the author's own account (T3)
     * @param subscriptionAllowed whether a subscription may pay: the company allows one for a trigger and the payer is
     *                            the author (ADR-0085, decision 7)
     */
    record Owner(UUID triggerId, UUID authorPrincipalId, UUID payerPrincipalId, boolean ownServersAllowed,
            boolean subscriptionAllowed) {
    }

    /**
     * Checks the settings as a save would, storing nothing (the editor's preview, and a create before its trigger
     * exists). Null input: the defaults.
     */
    void check(UUID companyId, UUID agentId, Owner owner, @Nullable AgentSettingsRequest input);

    /**
     * Makes the trigger's settings row, with the version its author accepted, and saves the settings given (null: the
     * defaults). In the trigger's creating transaction, after the trigger's row. Answers what was set, as
     * {@link #save} does.
     *
     * @throws IllegalArgumentException a deleted or unknown agent, or a version outside 1 to the current one
     */
    Map<String, Object> create(UUID companyId, UUID agentId, int acceptedVersion, Owner owner,
            @Nullable AgentSettingsRequest input);

    /**
     * Saves the trigger's settings as a whole; absent fields take their defaults. Answers what changed, as
     * {@code settings.changed} names it ({@code changed}, {@code systemsOff}, {@code toolsAsk}, {@code toolsOff},
     * {@code ownServers}); empty when nothing changed.
     */
    Map<String, Object> save(UUID companyId, UUID agentId, Owner owner, AgentSettingsRequest input);

    /**
     * Accepts the agent's widening up to {@code version} for the trigger (1 to the current: else
     * {@code 400 VALIDATION_FAILED}). Answers the version accepted before; a version at or below it changes nothing.
     */
    int accept(UUID companyId, UUID agentId, UUID triggerId, int version);

    /** The version the trigger's author accepted. */
    int acceptedVersion(UUID companyId, UUID triggerId);

    /** The trigger's settings in effect, as the API shows them. */
    AgentSettings view(UUID companyId, UUID agentId, UUID triggerId);

    /** Whether the trigger's settings add the author's own MCP servers (T3: then every row must be the author's). */
    boolean addsOwnServers(UUID companyId, UUID triggerId);
}
