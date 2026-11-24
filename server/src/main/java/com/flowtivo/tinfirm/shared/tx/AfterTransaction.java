package com.flowtivo.tinfirm.shared.tx;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;

/**
 * Database work that must not run while the thread still holds a transaction's connection (CONVENTIONS §10): work in a
 * transaction of its own that is asked for inside another transaction, or in a transaction's {@code afterCommit} and
 * {@code afterCompletion} callbacks (an {@code @TransactionalEventListener} among them), which still hold its
 * connection. A thread that holds one pooled connection while it asks for a second needs two at once: as many such
 * threads as the pool has connections each hold one and wait for another that none of them releases, until the pool's
 * timeout, and every other request of the replica waits behind them.
 * <p>
 * Such work is kept on the thread, and runs there, in the order it was kept, once the thread's outermost transaction
 * has ended and its connection went back to the pool ({@link ReleasingTransactionManager}): before the call that began
 * that transaction returns, so before a request is answered. It runs under the tenant context
 * ({@link TenantContextHolder}) and the security context the thread had when it was kept (the security context as the
 * thread holds it, unresolved: keeping work reads nothing), so a transaction it begins gets that tenant context,
 * whatever the thread entered meanwhile. Without a transaction of the application's transaction manager on the
 * thread, the work runs at once.
 * <p>
 * <b>Failures.</b> When the outermost transaction committed, the first failure of the kept work fails the call that
 * ended it, the others suppressed: as a {@link FailedAfterCommit} when a transaction that was neither read-only nor
 * {@link #BOOKKEEPING} committed in this piece of work ({@link #savedInThisWork}), else as it is. After a rollback a
 * failure is logged and the call's own error is the answer, but for {@link #runOrFail} work, whose failure replaces it.
 * When the commit or the rollback itself threw, that failure is thrown, and the kept work's are attached to it as
 * suppressed. Every kept work runs, whatever an earlier one threw, an {@link Error} included.
 * <p>
 * <b>The safety net.</b> {@link #endOfWork} ends a piece of work where the thread holds no transaction (the places are
 * in CONVENTIONS §10): work left kept there, or a transaction still counted, is a bug, logged as an error; the work is
 * run and the thread's state is reset. It also forgets {@link #savedInThisWork}.
 */
public final class AfterTransaction {

    private static final Logger log = LoggerFactory.getLogger(AfterTransaction.class);

    private static final ThreadLocal<State> STATE = new ThreadLocal<>();

    /** Whether a transaction neither read-only nor bookkeeping committed in the thread's current piece of work. */
    private static final ThreadLocal<Boolean> SAVED = new ThreadLocal<>();

    /** Transactions joined while the one they join was completing: in its callbacks, after its commit. */
    private static final AtomicLong JOINED_WHILE_COMPLETING = new AtomicLong();

    /**
     * The name ({@code TransactionTemplate}) or label ({@code @Transactional(label = …)}) of a transaction that is
     * bookkeeping, not a request's change: Spring Session's reads and saves of the session
     * ({@code TransactionManagerConfiguration}), the lookups that authenticate, authorize or route a request with
     * their marks of last use, and the records of who read or entered what (OWNER's reads, the break-glass). Its
     * commit leaves {@link #savedInThisWork} as it is.
     */
    public static final String BOOKKEEPING = "bookkeeping";

    private AfterTransaction() {
    }

    /** One kept work, with the contexts it was kept in; the security context's supplier, unresolved. */
    private record Kept(Runnable work, boolean failsTheCall, TenantContext tenant,
            Supplier<SecurityContext> security) {

        void run() {
            Supplier<SecurityContext> previous = SecurityContextHolder.getDeferredContext();
            SecurityContextHolder.setDeferredContext(security);
            try (TenantContextHolder.Scope ignored = TenantContextHolder.open(tenant)) {
                work.run();
            }
            finally {
                SecurityContextHolder.setDeferredContext(previous);
            }
        }
    }

    /** A transaction that holds a connection: its outcome as Spring reported it, and the work kept for its commit. */
    static final class Frame implements TransactionSynchronization {

        private final TransactionStatus status;

        private final boolean bookkeeping;

        private final List<Kept> onCommit = new ArrayList<>();

        private boolean completing;

        private boolean committed;

        Frame(TransactionStatus status, boolean bookkeeping) {
            this.status = status;
            this.bookkeeping = bookkeeping;
        }

        /**
         * First among the transaction's synchronizations, so that {@code completing} is set before any other's
         * {@code afterCommit} runs, and the outcome known before any other's {@code afterCompletion}.
         */
        @Override
        public int getOrder() {
            return org.springframework.core.Ordered.HIGHEST_PRECEDENCE;
        }

        /** The transaction committed; its other synchronizations' {@code afterCommit} callbacks follow. */
        @Override
        public void afterCommit() {
            completing = true;
        }

        /**
         * Spring calls every synchronization's {@code afterCompletion} once the transaction has completed, whatever
         * an {@code afterCommit} callback threw before (its {@code processCommit} calls it in a {@code finally}), and
         * catches what each one throws: so the outcome is known here even when the commit threw.
         */
        @Override
        public void afterCompletion(int outcome) {
            completing = true;
            committed = outcome == STATUS_COMMITTED;
        }

        boolean committed() {
            return committed;
        }

        boolean bookkeeping() {
            return bookkeeping;
        }
    }

    /** The thread's transactions that hold a connection, innermost first, and the work kept for after them. */
    private static final class State {

        private final Deque<Frame> frames = new ArrayDeque<>();

        private final List<Kept> pending = new ArrayList<>();
    }

    private static State state() {
        State state = STATE.get();
        if (state == null) {
            state = new State();
            STATE.set(state);
        }
        return state;
    }

    private static Kept kept(Runnable work, boolean failsTheCall) {
        return new Kept(work, failsTheCall, TenantContextHolder.current(), SecurityContextHolder.getDeferredContext());
    }

    /**
     * After the thread's transactions have ended, whatever their outcome: a record that must stay although the
     * transaction rolls back. After a rollback its failure is logged, and the call's own error is the answer.
     */
    public static void run(Runnable work) {
        keep(work, false);
    }

    /**
     * {@link #run}, but a failure after a rollback fails the call too, in place of the call's own error (which Spring
     * logs): for work whose failure failed the call before it was kept.
     */
    public static void runOrFail(Runnable work) {
        keep(work, true);
    }

    private static void keep(Runnable work, boolean failsTheCall) {
        if (!holding()) {
            work.run();
            return;
        }
        state().pending.add(kept(work, failsTheCall));
    }

    /**
     * After the current transaction has committed and the thread's transactions have ended; nothing when the current
     * transaction rolls back. Asked for in the transaction's work or in its {@code afterCommit} or
     * {@code afterCompletion} callbacks, it follows the current transaction's outcome as Spring reports it, also when a
     * callback threw after the commit. Outside a transaction (none on the thread, or a scope without one inside
     * another) it is {@link #run}.
     */
    public static void afterCommit(Runnable work) {
        State state = STATE.get();
        if (state == null || state.frames.isEmpty() || !TransactionSynchronizationManager.isActualTransactionActive()) {
            run(work);
            return;
        }
        state.frames.peek().onCommit.add(kept(work, false));
    }

    /** Whether the thread holds a transaction's connection: work asked for now must wait for its end. */
    static boolean holding() {
        State state = STATE.get();
        return state != null && !state.frames.isEmpty();
    }

    /**
     * Whether a transaction that was not read-only, and is not {@link #BOOKKEEPING}, committed in the thread's current
     * piece of work (a request, a job's run), until {@link #endOfWork}: a failure after it is a failure after a change
     * may already be saved. The server knows that such a transaction committed, not which rows it changed.
     */
    public static boolean savedInThisWork() {
        return SAVED.get() != null;
    }

    static void saved() {
        SAVED.set(Boolean.TRUE);
    }

    /**
     * How many transactions were joined while the transaction they joined was completing, in its {@code afterCommit}
     * or {@code afterCompletion} callbacks: such work runs on the finished transaction's connection, without its
     * tenant context, and what it writes is lost. For the tests' guard.
     */
    public static long joinedWhileCompleting() {
        return JOINED_WHILE_COMPLETING.get();
    }

    /** A transaction that joins another: an error when the other one is completing. */
    static void joined() {
        State state = STATE.get();
        if (state != null && !state.frames.isEmpty() && state.frames.peek().completing) {
            JOINED_WHILE_COMPLETING.incrementAndGet();
            log.error("A transaction was joined while the one it joins was completing (in its afterCommit or "
                    + "afterCompletion callbacks): its work runs on the finished transaction's connection without its "
                    + "tenant context; keep it with AfterTransaction instead",
                    new IllegalStateException("joined while completing"));
        }
    }

    /** The transaction manager began a transaction that holds a connection. */
    static Frame began(TransactionStatus status, boolean bookkeeping) {
        Frame frame = new Frame(status, bookkeeping);
        state().frames.push(frame);
        return frame;
    }

    /** The frame of the status, if it is on the thread's stack; null for one that is not (ended, or another's). */
    static @Nullable Frame frameOf(TransactionStatus status) {
        State state = STATE.get();
        if (state == null) {
            return null;
        }
        for (Frame frame : state.frames) {
            if (frame.status == status) {
                return frame;
            }
        }
        return null;
    }

    /**
     * The transaction manager's commit or rollback of the frame's transaction has returned or thrown, and released its
     * connection. When it was the thread's outermost, the kept work runs.
     *
     * @return the kept work's failures, in the order of the work; empty unless the outermost transaction ended
     */
    static List<Failure> ended(Frame frame) {
        State state = STATE.get();
        if (state == null || !state.frames.contains(frame)) {
            return List.of();
        }
        while (state.frames.peek() != frame) {
            state.frames.pop();
            log.error("A transaction ended before one it holds inside it, which was never ended: that one is no "
                    + "longer counted");
        }
        state.frames.pop();
        if (frame.committed) {
            state.pending.addAll(frame.onCommit);
        }
        if (!state.frames.isEmpty()) {
            return List.of();
        }
        return drain(state);
    }

    /** A kept work's failure, and whether it fails the call after a rollback too. */
    record Failure(Throwable thrown, boolean failsTheCall) {
    }

    /** Runs the kept work, and the work it keeps in turn, each whatever the others threw. */
    private static List<Failure> drain(State state) {
        List<Failure> failures = new ArrayList<>();
        while (!state.pending.isEmpty()) {
            List<Kept> batch = new ArrayList<>(state.pending);
            state.pending.clear();
            for (Kept kept : batch) {
                try {
                    kept.run();
                }
                catch (Throwable ex) {
                    failures.add(new Failure(ex, kept.failsTheCall()));
                }
            }
        }
        if (state.frames.isEmpty() && STATE.get() == state) {
            STATE.remove();
        }
        return failures;
    }

    /**
     * The end of a piece of work (a request, a scheduled job's run, a socket's callback) where the thread holds no
     * transaction: work still kept, or a transaction still counted, is a bug of the thread's work (a transaction never
     * ended), logged as an error that names it. The work is run, its failures logged, and the thread's state reset;
     * {@link #savedInThisWork} is forgotten.
     *
     * @param where what ended, for the log
     */
    public static void endOfWork(String where) {
        SAVED.remove();
        State state = STATE.get();
        if (state == null) {
            return;
        }
        STATE.remove();
        if (state.frames.isEmpty() && state.pending.isEmpty()) {
            return;
        }
        int dropped = state.frames.stream().mapToInt(frame -> frame.onCommit.size()).sum();
        log.error("{} ended with {} transactions still counted and {} kept works left on its thread: the works run now; "
                + "{} kept for after a commit that never came are dropped", where, state.frames.size(),
                state.pending.size(), dropped);
        state.frames.clear();
        for (Failure failure : drain(state)) {
            log.error("Work left kept at the end of {} failed", where, failure.thrown());
        }
        SAVED.remove();
    }
}
