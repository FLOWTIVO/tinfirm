package com.flowtivo.tinfirm.shared.tenant;

/**
 * Holds the {@link TenantContext} of the current thread (one request per virtual thread).
 * <p>
 * Only establishes <em>what</em> the database should see. Writing it into the transaction is done by
 * {@link TenantAwareDataSource} when a transaction begins, and by {@link TenantScope#enterCompany} when the
 * company is authorised in the middle of a transaction.
 */
public final class TenantContextHolder {

    private static final ThreadLocal<TenantContext> CURRENT = new ThreadLocal<>();

    private TenantContextHolder() {
    }

    public static TenantContext current() {
        TenantContext context = CURRENT.get();
        return context != null ? context : TenantContext.EMPTY;
    }

    /**
     * Sets the context until the returned scope is closed, then restores the previous one.
     */
    public static Scope open(TenantContext context) {
        TenantContext previous = CURRENT.get();
        CURRENT.set(context);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            }
            else {
                CURRENT.set(previous);
            }
        };
    }

    static void replace(TenantContext context) {
        CURRENT.set(context);
    }

    /** A context scope that restores the previous context on close. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
