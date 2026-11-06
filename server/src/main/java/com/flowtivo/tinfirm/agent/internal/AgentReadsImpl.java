package com.flowtivo.tinfirm.agent.internal;

import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentReads;
import com.flowtivo.tinfirm.shared.api.model.Agent;
import com.flowtivo.tinfirm.shared.api.model.AgentPage;
import com.flowtivo.tinfirm.shared.api.model.AgentReadiness;

/** {@link AgentReads} over the services the REST controller calls. */
@Component
class AgentReadsImpl implements AgentReads {

    private final AgentService agents;

    private final ReadinessService readiness;

    AgentReadsImpl(AgentService agents, ReadinessService readiness) {
        this.agents = agents;
        this.readiness = readiness;
    }

    @Override
    public AgentPage list(UUID companyId, @Nullable Boolean usable, @Nullable Integer limit, @Nullable String cursor) {
        return agents.list(companyId, usable, limit, cursor);
    }

    @Override
    public Agent get(UUID companyId, UUID agentId) {
        return agents.get(companyId, agentId);
    }

    @Override
    public AgentReadiness readiness(UUID companyId, UUID agentId) {
        return readiness.readiness(companyId, agentId);
    }

    @Override
    public Optional<UUID> idOfHandle(UUID companyId, String handle) {
        return agents.idOfHandle(companyId, handle);
    }
}
