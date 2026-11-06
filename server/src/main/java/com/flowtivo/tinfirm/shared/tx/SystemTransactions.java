package com.flowtivo.tinfirm.shared.tx;

import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.shared.security.SystemContext;

/**
 * Transactions of trusted server-side work (runner protocol, dispatcher, reaper) in the matching tenant context.
 * The transaction begins inside the context, so {@code TenantAwareDataSource} writes it into the transaction.
 */
@Component
public class SystemTransactions {

    private final TransactionTemplate transactions;

    public SystemTransactions(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** A transaction scoped to one company, as actor SYSTEM without instance-admin rights. */
    public <T> T inCompany(UUID companyId, Supplier<T> work) {
        return SystemContext.callInCompany(companyId, () -> transactions.execute(status -> work.get()));
    }

    public void runInCompany(UUID companyId, Runnable work) {
        SystemContext.runInCompany(companyId, () -> transactions.executeWithoutResult(status -> work.run()));
    }

    /**
     * An instance-wide transaction, as actor SYSTEM with instance-admin rights: only for work on the instance's own
     * rows, such as the daily check of the instance's audit chain.
     */
    public <T> T instanceWide(Supplier<T> work) {
        return SystemContext.call(() -> transactions.execute(status -> work.get()));
    }

    /**
     * A transaction without any company: row-level security hides every tenant row. Used only to call the narrow
     * {@code SECURITY DEFINER} lookup functions that return ids and company ids across companies.
     */
    public <T> T unscoped(Supplier<T> work) {
        return SystemContext.callUnscoped(() -> transactions.execute(status -> work.get()));
    }
}
