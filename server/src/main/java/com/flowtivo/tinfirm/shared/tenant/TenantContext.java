package com.flowtivo.tinfirm.shared.tenant;

import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * The values that row-level security policies see for the current transaction.
 *
 * @param userId        the calling user ({@code app.user_id}); null for anonymous and system work
 * @param instanceAdmin whether the caller is an instance admin ({@code app.instance_admin}): it lists every company
 *                      and reads the instance chain of the audit log, never a company's other rows, which need the
 *                      company's context
 * @param companyId     the company the caller has been authorised for ({@code app.company_id}); null when none
 * @param system        trusted server-side work across companies ({@code app.system}): only {@link #SYSTEM}, never a
 *                      caller's request (CONVENTIONS §7)
 */
public record TenantContext(@Nullable UUID userId, boolean instanceAdmin, @Nullable UUID companyId, boolean system) {

    /** No user, no company: every tenant policy denies. */
    public static final TenantContext EMPTY = new TenantContext(null, false, null, false);

    /** Trusted server-side work (bootstrap, the vault's rewrap) that spans companies. */
    public static final TenantContext SYSTEM = new TenantContext(null, true, null, true);

    /**
     * Trusted server-side work for one company (runner protocol, dispatcher, reaper): no user, no instance-admin
     * rights, and row-level security scoped to that company. The company must come from server-side data (a runner
     * host token, a row id returned by a narrow lookup function), never from client input.
     */
    public static TenantContext forCompany(UUID companyId) {
        return new TenantContext(null, false, companyId, false);
    }

    public static TenantContext forUser(UUID userId, boolean instanceAdmin) {
        return new TenantContext(userId, instanceAdmin, null, false);
    }

    public TenantContext withCompany(UUID company) {
        return new TenantContext(userId, instanceAdmin, company, system);
    }
}
