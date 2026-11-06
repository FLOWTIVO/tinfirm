package com.flowtivo.tinfirm.trigger;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The triggers of a principal, for the modules above (package 6's spend, its views and budget alerts: the package 6
 * contract, §5.2, §5.3; the package 4 contract, §5.13). Runs in the caller's transaction and tenant context: row-level
 * security limits every lookup to the company of that context. It answers ids, names and rows, never who may see a
 * trigger's own page ({@code TriggerAudience}).
 */
public interface TriggerDirectory {

    /**
     * The triggers, live or deleted, that the principal authors or whose team they are in (T12): those whose runs'
     * spend they see. None for a service identity.
     */
    Set<UUID> ofPrincipal(UUID companyId, UUID principalId);

    /** The triggers' names, a deleted one's last name included; an unknown id is absent. */
    Map<UUID, String> names(UUID companyId, Collection<UUID> triggerIds);

    /** The live triggers the principal pays (their payer), active or paused, in the order of their names. */
    List<TriggerInfo> livePaidBy(UUID companyId, UUID payerPrincipalId);
}
