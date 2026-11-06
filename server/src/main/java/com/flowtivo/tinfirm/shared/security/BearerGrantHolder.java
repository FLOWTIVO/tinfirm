package com.flowtivo.tinfirm.shared.security;

import java.util.Optional;

/**
 * Holds the {@link BearerGrant} of the current request (one request per virtual thread), set by the bearer filter
 * chain of the MCP endpoint for the rest of the request. Browser-session requests and system work have none.
 * <p>
 * Read it for the scope check of a tool and for the token or grant id in audit details.
 */
public final class BearerGrantHolder {

    private static final ThreadLocal<BearerGrant> CURRENT = new ThreadLocal<>();

    private BearerGrantHolder() {
    }

    /** The grant of the current request, if a bearer credential authenticated it. */
    public static Optional<BearerGrant> current() {
        return Optional.ofNullable(CURRENT.get());
    }

    /** Sets the grant until the returned scope is closed, then restores the previous one. */
    public static Scope open(BearerGrant grant) {
        BearerGrant previous = CURRENT.get();
        CURRENT.set(grant);
        return () -> {
            if (previous == null) {
                CURRENT.remove();
            }
            else {
                CURRENT.set(previous);
            }
        };
    }

    /** A grant scope that restores the previous grant on close. */
    @FunctionalInterface
    public interface Scope extends AutoCloseable {

        @Override
        void close();
    }
}
