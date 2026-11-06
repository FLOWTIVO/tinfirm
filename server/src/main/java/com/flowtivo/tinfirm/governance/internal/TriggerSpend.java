package com.flowtivo.tinfirm.governance.internal;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The seam to package 4's triggers (the package 6 contract, §5.2, §5.3): a run's trigger ({@code run.trigger_id}), the
 * triggers whose audience (author, team) a principal is, their names, and who authors the live triggers a principal
 * pays. {@link TriggersSpend} answers it over the trigger module's public {@code TriggerDirectory}; every rule of this
 * module reads triggers through here, so that a test stands them in with one bean.
 */
interface TriggerSpend {

    /** The trigger whose run this is; null for a person's conversation. */
    @Nullable UUID triggerOf(UUID companyId, UUID runId);

    /** The triggers whose author or team member the principal is: they see those triggers' spend (G1). */
    Set<UUID> audienceOf(UUID companyId, UUID principalId);

    /** The triggers' names, a deleted one's last name included; unknown ids are absent. */
    Map<UUID, String> names(UUID companyId, Collection<UUID> triggerIds);

    /** The principals who author the live triggers the payer pays (§5.3: told of a service identity's budget). */
    Set<UUID> authorsPaidBy(UUID companyId, UUID payerPrincipalId);

    /** How many live triggers the payer pays (§5.3: the counts at 100 %). */
    int livePaidBy(UUID companyId, UUID payerPrincipalId);
}
