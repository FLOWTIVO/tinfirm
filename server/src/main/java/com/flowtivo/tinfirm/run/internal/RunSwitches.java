package com.flowtivo.tinfirm.run.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;

import com.flowtivo.tinfirm.company.CompanyRunsDependents;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.run.RunCancellations;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * The run module's part of the kill switches (the package 6 contract, §5.4): it cancels what a switch stops
 * ({@link RunCancellations}), what the company's stop stops ({@link CompanyRunsDependents}: the dispatcher's step 0
 * holds the rest), and, after the commit of a principal's suspension, the turns it acts for or pays, in a transaction
 * of its own on the module's workers, as SYSTEM.
 */
@Component
class RunSwitches implements RunCancellations, CompanyRunsDependents {

    private static final Logger log = LoggerFactory.getLogger(RunSwitches.class);

    private final RunStore runs;

    private final RunLocks locks;

    private final TurnStarter starter;

    private final RunWorkers workers;

    private final SystemTransactions transactions;

    RunSwitches(RunStore runs, RunLocks locks, TurnStarter starter, RunWorkers workers,
            SystemTransactions transactions) {
        this.runs = runs;
        this.locks = locks;
        this.starter = starter;
        this.workers = workers;
        this.transactions = transactions;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> cancelOfPrincipal(UUID companyId, UUID principalId) {
        return cancel(runs.activeOfPrincipal(principalId), PRINCIPAL_SUSPENDED);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> cancelRunningOfCompany(UUID companyId) {
        return cancel(runs.runningOfCompany(companyId), COMPANY_STOPPED);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> cancelRunningOfConversations(UUID companyId, Collection<UUID> conversationIds) {
        return cancel(runs.runningOfConversations(companyId, conversationIds), SYSTEM_PAUSED);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> runsStopping(UUID companyId) {
        return cancelRunningOfCompany(companyId);
    }

    /** A principal was suspended (G6): its running turns are cancelled after the commit, as SYSTEM. */
    @TransactionalEventListener
    void principalSuspended(PrincipalEvents.PrincipalSuspended event) {
        workers.execute("cancel of suspended principal " + event.principalId(), () -> {
            try {
                List<UUID> cancelled = transactions.inCompany(event.companyId(),
                        () -> cancelOfPrincipal(event.companyId(), event.principalId()));
                if (!cancelled.isEmpty()) {
                    log.info("Principal {} was suspended: {} runs cancelled", event.principalId(), cancelled.size());
                }
            }
            catch (RuntimeException ex) {
                log.warn("The runs of suspended principal {} could not be cancelled", event.principalId(), ex);
            }
        });
    }

    private List<UUID> cancel(List<RunStore.RunRow> active, String cause) {
        locks.lockConversations(active);
        List<UUID> cancelled = new ArrayList<>();
        for (RunStore.RunRow found : active) {
            RunStore.RunRow run = runs.lock(found.id()).orElse(null);
            if (run != null && starter.cancel(run, cause)) {
                cancelled.add(run.id());
            }
        }
        return cancelled;
    }
}
