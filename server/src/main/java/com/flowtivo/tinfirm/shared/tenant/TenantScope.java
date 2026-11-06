package com.flowtivo.tinfirm.shared.tenant;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;

import javax.sql.DataSource;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Switches the tenant context to a company once the caller has been authorised for it.
 * <p>
 * Row-level security trusts {@code app.company_id}, so {@link #enterCompany(UUID)} is called only with a company the
 * caller was authorised for or read from the server's own rows, never with an id from a request's input: the company
 * access guard, after its check; the company module's creation, declared companies and provisioned memberships (the
 * provider's own company); SCIM, for the company its token names; the runner host service, for its host's presences;
 * suspensions, for each company of a deactivated user; and the vault's reveal and scan, which first refuse a context
 * of another company (the security review of 2026-10-27, §7). {@link #eachIn} sets back, at the commit, contexts the
 * transaction had already been given, for the audit log's appends.
 */
@Component
public class TenantScope {

    private final JdbcTemplate jdbcTemplate;

    public TenantScope(DataSource dataSource) {
        this.jdbcTemplate = new JdbcTemplate(dataSource);
    }

    /**
     * Makes {@code companyId} the company of the tenant context: for the rest of the request, and for the
     * transaction that is currently running (if any).
     */
    public void enterCompany(UUID companyId) {
        TenantContext context = TenantContextHolder.current().withCompany(companyId);
        TenantContextHolder.replace(context);
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
                TenantSettings.apply(connection, context);
                return null;
            });
        }
    }

    /**
     * Does each item's work in the current transaction under the tenant context that item names, then sets back the
     * transaction's own: for work the transaction does at its commit on behalf of its earlier calls, each under the
     * context that call had (the audit log appends each event under the context it was recorded in, CONVENTIONS §8),
     * when the transaction entered several companies meanwhile. A context comes only from the thread's own context at
     * such a call ({@link TenantContextHolder#current()}), which the access guard authorised, never from a caller's
     * input. The thread's context is left as it is.
     */
    public <T> void eachIn(List<T> items, Function<T, TenantContext> contextOf, Consumer<T> work) {
        if (items.isEmpty()) {
            return;
        }
        jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
            TenantContext own = TenantSettings.read(connection);
            TenantContext inEffect = own;
            for (T item : items) {
                TenantContext wanted = contextOf.apply(item);
                if (!wanted.equals(inEffect)) {
                    TenantSettings.apply(connection, wanted);
                    inEffect = wanted;
                }
                work.accept(item);
            }
            // A failure above leaves the transaction to roll back, with its settings.
            if (!inEffect.equals(own)) {
                TenantSettings.apply(connection, own);
            }
            return null;
        });
    }
}
