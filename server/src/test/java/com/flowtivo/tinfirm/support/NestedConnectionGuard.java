package com.flowtivo.tinfirm.support;

import static org.assertj.core.api.Assertions.fail;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

import com.flowtivo.tinfirm.shared.tx.AfterTransaction;

/**
 * Fails a test class during which a thread asked the pool for a database connection while it held one
 * ({@link ConnectionWatch}), outside {@link #ALLOWED}, or a transaction was joined while the one it joins was completing
 * ({@code AfterTransaction.joinedWhileCompleting}): as many such threads as the pool has connections wait for each
 * other until the pool's timeout (CONVENTIONS §10). Registered for every test class ({@code junit-platform.properties});
 * a class that starts no server sees nothing. Work that needs a transaction of its own while the thread holds one runs
 * once the thread's transaction has released its connection ({@code shared.tx.AfterTransaction}). It also ends the
 * class's fixtures of {@link ConnectionWatch} ({@code timeOutAt}, {@code plantAt}), and fails the class on one that
 * was never used: a frame's name that matched nothing.
 * <p>
 * What it cannot see: a thread that holds a connection while it waits for another thread's acquisition (a
 * {@code Future.get()} inside a transaction), which needs two connections all the same; and whose thread nested, when
 * a background job of another cached context did it during this class.
 */
public final class NestedConnectionGuard implements BeforeAllCallback, AfterAllCallback {

    /**
     * The tests allowed both, by the frames of their classes: the transaction manager's own tests, which take a second
     * connection inside a transaction of their own on purpose, and join a completing transaction to see it reported.
     */
    static final List<String> ALLOWED = List.of("com.flowtivo.tinfirm.shared.tx.AfterTransactionTest");

    private static final String JOINED = "joined";

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace
        .create(NestedConnectionGuard.class);

    @Override
    public void beforeAll(ExtensionContext context) {
        context.getStore(NAMESPACE).put(ConnectionWatch.Mark.class, ConnectionWatch.mark());
        context.getStore(NAMESPACE).put(JOINED, AfterTransaction.joinedWhileCompleting());
    }

    @Override
    public void afterAll(ExtensionContext context) {
        java.util.List<String> unused = ConnectionWatch.endOfClass();
        if (!unused.isEmpty()) {
            fail("Fixtures of ConnectionWatch that " + context.getDisplayName() + " never used (a frame's name that "
                    + "matched nothing?): " + unused);
        }
        ConnectionWatch.Mark mark = context.getStore(NAMESPACE).get(ConnectionWatch.Mark.class,
                ConnectionWatch.Mark.class);
        if (mark == null) {
            return;
        }
        Long joined = context.getStore(NAMESPACE).get(JOINED, Long.class);
        if (joined != null && AfterTransaction.joinedWhileCompleting() > joined
                && ALLOWED.stream().noneMatch(context.getRequiredTestClass().getName()::startsWith)) {
            fail("A transaction was joined while the one it joins was completing, during " + context.getDisplayName()
                    + " (CONVENTIONS §10; shared.tx.AfterTransaction): see the error logged with its stack");
        }
        Map<ConnectionWatch.Nested, Long> added = ConnectionWatch.nested()
            .entrySet()
            .stream()
            .filter(entry -> entry.getValue() > mark.seen().getOrDefault(entry.getKey(), 0L))
            .filter(entry -> entry.getKey().inner().stream().noneMatch(frame -> ALLOWED.stream()
                .anyMatch(frame::startsWith)))
            .collect(Collectors.toMap(Map.Entry::getKey,
                    entry -> entry.getValue() - mark.seen().getOrDefault(entry.getKey(), 0L)));
        if (!added.isEmpty()) {
            fail("A thread asked the pool for a database connection while it held one, during "
                    + context.getDisplayName() + " (CONVENTIONS §10; shared.tx.AfterTransaction). The thread may be "
                    + "this class's, or a background thread (a scheduled job, an executor) of any test context this "
                    + "JVM keeps, and the first class that starts a context also sees its start; the stacks say "
                    + "which:\n"
                    + added.entrySet()
                        .stream()
                        .map(entry -> entry.getValue() + "x\n" + entry.getKey())
                        .collect(Collectors.joining("\n\n")));
        }
    }
}
