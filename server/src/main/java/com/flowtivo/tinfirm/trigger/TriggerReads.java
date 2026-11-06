package com.flowtivo.tinfirm.trigger;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.AgentSettings;
import com.flowtivo.tinfirm.shared.api.model.Trigger;
import com.flowtivo.tinfirm.shared.api.model.TriggerFiringPage;
import com.flowtivo.tinfirm.shared.api.model.TriggerPage;

/**
 * Triggers for the caller's AI client (the MCP endpoint, ADR-0045): the reads of the Triggers API, with its inputs,
 * its DTOs and its rules, through the same service (T15): the caller sees the triggers whose author or team they are,
 * and OWNER and ADMIN every trigger; anyone else gets 404 for a trigger, never 403. Nothing here creates, changes,
 * pauses or runs a trigger (the package 6 contract, open point 15).
 */
public interface TriggerReads {

    /** {@code listTriggers}: the triggers the caller sees, by name, filtered, a page. */
    TriggerPage list(UUID companyId, Filter filter, @Nullable Integer limit, @Nullable String cursor);

    /** {@code getTrigger}: the trigger in full, as the caller sees it. */
    Trigger get(UUID companyId, UUID triggerId);

    /** {@code getTriggerSettings}: the trigger's settings of its agent (its model access among them). */
    AgentSettings settings(UUID companyId, UUID triggerId);

    /** {@code listTriggerFirings}: its firings, newest first, a page. */
    TriggerFiringPage firings(UUID companyId, UUID triggerId, @Nullable Integer limit, @Nullable String cursor);

    /**
     * The filters of {@code listTriggers}.
     *
     * @param accountHolderPrincipalId the triggers whose accounts' table holds an account of that principal
     */
    record Filter(@Nullable UUID systemId, @Nullable UUID agentId, @Nullable UUID authorId,
            @Nullable UUID teamGroupId, @Nullable TriggerStatus status, @Nullable UUID accountHolderPrincipalId) {
    }
}
