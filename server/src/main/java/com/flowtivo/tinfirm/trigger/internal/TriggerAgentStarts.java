package com.flowtivo.tinfirm.trigger.internal;

import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentStarts;

/**
 * The agent module's {@link AgentStarts}: an agent whose live trigger, paused or not, has a source anyone may write to
 * ({@code TriggerSource.openToWriters}, as {@link TriggerExposures} counts them) starts runs from outside the company.
 */
@Component
class TriggerAgentStarts implements AgentStarts {

    private final TriggerStore store;

    TriggerAgentStarts(TriggerStore store) {
        this.store = store;
    }

    @Override
    public boolean startsFromOutside(UUID companyId, UUID agentId) {
        return store.liveOfAgent(companyId, agentId).stream().anyMatch(trigger -> trigger.source().openToWriters());
    }
}
