package com.flowtivo.tinfirm.run;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The kill switches' cancels of running turns (the package 6 contract, §5.4), for the modules that pull them. Each runs
 * in the caller's transaction (a mandatory one) and tenant context, locks the runs' conversations first, in the order
 * of their ids, then each run (CONVENTIONS §10), and cancels it as a person's cancel does: a queued run at once, a
 * running one through its host after the commit. Each is audited {@code run.cancelled} with the switch as its
 * {@code cause}. Answers the runs it cancelled.
 */
public interface RunCancellations {

    /** {@code cause} {@code principal.suspended}: the QUEUED and RUNNING runs that act for or are paid by it. */
    String PRINCIPAL_SUSPENDED = "principal.suspended";

    /** {@code cause} {@code company.runs_stopped}. */
    String COMPANY_STOPPED = "company.runs_stopped";

    /** {@code cause} {@code system.paused}. */
    String SYSTEM_PAUSED = "system.paused";

    /** The active runs the principal acts for or pays, a suspended one's. */
    List<UUID> cancelOfPrincipal(UUID companyId, UUID principalId);

    /** The company's RUNNING runs and those assigned to a host; the queued ones wait {@code COMPANY_STOPPED}. */
    List<UUID> cancelRunningOfCompany(UUID companyId);

    /** The RUNNING runs of the conversations, for a system paused with {@code stopRunning}. */
    List<UUID> cancelRunningOfConversations(UUID companyId, Collection<UUID> conversationIds);
}
