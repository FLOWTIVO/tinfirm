package com.flowtivo.tinfirm.shared.security;

import java.util.UUID;
import java.util.function.Supplier;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;

import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;

/**
 * Runs trusted server-side work as actor {@code SYSTEM}. Transactions must be started inside the callback so that
 * they pick up the tenant context it establishes.
 * <ul>
 * <li>{@link #call}: instance-wide (bootstrap and maintenance that spans companies, such as the vault's rewrap): the
 * only context with {@code app.system}, which row-level security lets see every company's rows (CONVENTIONS §7).</li>
 * <li>{@link #callInCompany}: one company, without instance-admin rights (runner protocol, dispatcher, reaper).
 * The company id must come from server-side data, never from client input.</li>
 * <li>{@link #callUnscoped}: no company and no instance-admin rights. Row-level security hides every tenant row;
 * only the narrow {@code SECURITY DEFINER} lookup functions return ids across companies.</li>
 * </ul>
 */
public final class SystemContext {

    private SystemContext() {
    }

    public static <T> T call(Supplier<T> work) {
        return callWith(TenantContext.SYSTEM, work);
    }

    public static <T> T callInCompany(UUID companyId, Supplier<T> work) {
        return callWith(TenantContext.forCompany(companyId), work);
    }

    public static void runInCompany(UUID companyId, Runnable work) {
        callWith(TenantContext.forCompany(companyId), () -> {
            work.run();
            return null;
        });
    }

    public static <T> T callUnscoped(Supplier<T> work) {
        return callWith(TenantContext.EMPTY, work);
    }

    private static <T> T callWith(TenantContext tenant, Supplier<T> work) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext system = SecurityContextHolder.createEmptyContext();
        system.setAuthentication(SystemAuthentication.INSTANCE);
        SecurityContextHolder.setContext(system);
        try (TenantContextHolder.Scope ignored = TenantContextHolder.open(tenant)) {
            return work.get();
        }
        finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
