package com.flowtivo.tinfirm.shared.sql;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.postgresql.util.PSQLException;
import org.postgresql.util.ServerErrorMessage;

/**
 * Which constraint PostgreSQL named when it refused a statement (CONVENTIONS §10). A service that answers a unique
 * index's or a check's refusal as a conflict (another save took the name at the same moment) asks for the
 * constraint by name here, as the server reported it in its error's {@code constraint} field, never by searching the
 * message's text, which the server words and may translate.
 */
public final class Constraints {

    /** How many exceptions of a chain are looked at; a longer one is not a refusal this answers. */
    private static final int MAX_EXCEPTIONS = 32;

    private Constraints() {
    }

    /**
     * The constraint or index the database named in its refusal: the first {@link PSQLException}, breadth first along
     * each exception's cause and a {@link SQLException}'s next exception (a batch's), with a server error that names
     * one. Both links are followed, as a batch's exception may carry a cause of its own besides the refusal.
     *
     * @return its name, or null when no PostgreSQL error along the chain names a constraint
     */
    public static @Nullable String nameOf(@Nullable Throwable ex) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> pending = new ArrayDeque<>();
        if (ex != null) {
            pending.add(ex);
        }
        while (!pending.isEmpty() && seen.size() < MAX_EXCEPTIONS) {
            Throwable current = pending.remove();
            if (!seen.add(current)) {
                continue;
            }
            if (current instanceof PSQLException psql) {
                ServerErrorMessage error = psql.getServerErrorMessage();
                if (error != null && error.getConstraint() != null) {
                    return error.getConstraint();
                }
            }
            Throwable cause = current.getCause();
            if (cause != null) {
                pending.add(cause);
            }
            if (current instanceof SQLException sql) {
                SQLException nextException = sql.getNextException();
                if (nextException != null) {
                    pending.add(nextException);
                }
            }
        }
        return null;
    }

    /** Whether the database's refusal named one of these constraints or indexes. */
    public static boolean violated(@Nullable Throwable ex, String... names) {
        String violated = nameOf(ex);
        if (violated != null) {
            for (String name : names) {
                if (violated.equals(name)) {
                    return true;
                }
            }
        }
        return false;
    }
}
