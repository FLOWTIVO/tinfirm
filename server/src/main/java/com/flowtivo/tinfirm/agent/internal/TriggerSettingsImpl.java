package com.flowtivo.tinfirm.agent.internal;

import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.TriggerSettings;
import com.flowtivo.tinfirm.shared.api.model.AgentSettings;
import com.flowtivo.tinfirm.shared.api.model.AgentSettingsRequest;

/** {@link TriggerSettings} for the trigger module, on {@link SettingsService}: in the caller's transaction. */
@Component
class TriggerSettingsImpl implements TriggerSettings {

    private final SettingsService settings;

    TriggerSettingsImpl(SettingsService settings) {
        this.settings = settings;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void check(UUID companyId, UUID agentId, Owner owner, @Nullable AgentSettingsRequest input) {
        settings.checkTrigger(companyId, agentId, owner, input);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> create(UUID companyId, UUID agentId, int acceptedVersion, Owner owner,
            @Nullable AgentSettingsRequest input) {
        return settings.createTrigger(companyId, agentId, acceptedVersion, owner, input);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<String, Object> save(UUID companyId, UUID agentId, Owner owner, AgentSettingsRequest input) {
        return settings.saveTrigger(companyId, agentId, owner, input);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int accept(UUID companyId, UUID agentId, UUID triggerId, int version) {
        return settings.acceptTrigger(companyId, agentId, triggerId, version);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public int acceptedVersion(UUID companyId, UUID triggerId) {
        return settings.triggerAcceptedVersion(companyId, triggerId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public AgentSettings view(UUID companyId, UUID agentId, UUID triggerId) {
        return settings.viewTrigger(companyId, agentId, triggerId);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean addsOwnServers(UUID companyId, UUID triggerId) {
        return settings.triggerAddsOwnServers(companyId, triggerId);
    }
}
