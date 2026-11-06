package com.flowtivo.tinfirm.trigger.internal;

import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.api.model.AgentSettings;
import com.flowtivo.tinfirm.shared.api.model.Trigger;
import com.flowtivo.tinfirm.shared.api.model.TriggerFiringPage;
import com.flowtivo.tinfirm.shared.api.model.TriggerPage;
import com.flowtivo.tinfirm.trigger.TriggerReads;

/**
 * The Triggers API's reads for the caller's AI client ({@link TriggerReads}): the calls of {@link TriggersController},
 * to the same service, with the same inputs, so the client sees exactly what the API shows the caller.
 */
@Component
class TriggerClientReads implements TriggerReads {

    private final TriggerService triggers;

    TriggerClientReads(TriggerService triggers) {
        this.triggers = triggers;
    }

    @Override
    public TriggerPage list(UUID companyId, Filter filter, @Nullable Integer limit, @Nullable String cursor) {
        return triggers.list(companyId, filter.systemId(), filter.agentId(), filter.authorId(), filter.teamGroupId(),
                filter.status(), filter.accountHolderPrincipalId(), limit, cursor);
    }

    @Override
    public Trigger get(UUID companyId, UUID triggerId) {
        return triggers.get(companyId, triggerId);
    }

    @Override
    public AgentSettings settings(UUID companyId, UUID triggerId) {
        return triggers.settings(companyId, triggerId);
    }

    @Override
    public TriggerFiringPage firings(UUID companyId, UUID triggerId, @Nullable Integer limit,
            @Nullable String cursor) {
        return triggers.firings(companyId, triggerId, limit, cursor);
    }
}
