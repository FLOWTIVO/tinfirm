package com.flowtivo.tinfirm.shared.tx;

import java.util.Collection;
import java.util.List;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The application's transaction manager: Spring Boot's JPA transaction manager, wrapped, which runs the work kept in
 * {@link AfterTransaction} once the thread's outermost transaction has ended. It counts each transaction the JPA
 * manager begins that holds a connection ({@link TransactionStatus#isNewTransaction()}: the outermost, and each of its
 * own inside another, {@code REQUIRES_NEW}), and runs the kept work after the JPA manager's {@code commit} or
 * {@code rollback} of the outermost one has returned or thrown: after all of Spring's own completion (the
 * synchronizations' callbacks, the connection's release, the resume of what it suspended), none of which kept work can
 * skip or hide. A transaction that joins another, a savepoint ({@code NESTED}; the JPA manager takes none) and a scope
 * without a transaction ({@code SUPPORTS}, {@code NOT_SUPPORTED}, {@code NEVER}) are not counted: inside a transaction
 * they leave the kept work to its end.
 * <p>
 * The outcome of each counted transaction is the one Spring reports to the synchronization registered with it
 * ({@code afterCompletion}), never inferred from whether {@code commit} returned: a commit whose {@code afterCommit}
 * callback threw has committed. A commit or rollback of a status that is not on the thread's stack (ended already,
 * or another thread's) is passed to the JPA manager and changes nothing here.
 * <p>
 * The kept work's failures ({@link AfterTransaction}): after a commit, the first is thrown, the others suppressed, as a
 * {@link FailedAfterCommit} when a transaction that was not read-only committed in this piece of work; after a
 * rollback they are logged, but for work kept with {@link AfterTransaction#runOrFail}; when the commit or the rollback
 * threw, they are suppressed in its failure, and a commit that threw after it committed is a failure after the commit
 * too. An {@link Error} is thrown as it is, with a {@link FailedAfterCommit} suppressed in it after a saved change. The
 * execution listeners (the audit's, CONVENTIONS §8) and Spring Boot's customizers are the JPA manager's.
 */
final class ReleasingTransactionManager implements PlatformTransactionManager, ConfigurableTransactionManager {

    private static final Logger log = LoggerFactory.getLogger(ReleasingTransactionManager.class);

    private final PlatformTransactionManager delegate;

    private final ConfigurableTransactionManager listeners;

    <T extends PlatformTransactionManager & ConfigurableTransactionManager> ReleasingTransactionManager(T delegate) {
        this.delegate = delegate;
        this.listeners = delegate;
    }

    /** The JPA transaction manager this one wraps. */
    PlatformTransactionManager delegate() {
        return delegate;
    }

    @Override
    public TransactionStatus getTransaction(@Nullable TransactionDefinition definition) {
        TransactionStatus status = delegate.getTransaction(definition);
        if (status.isNewTransaction()) {
            AfterTransaction.Frame frame = AfterTransaction.began(status, bookkeeping(definition));
            TransactionSynchronizationManager.registerSynchronization(frame);
        }
        else if (status.hasTransaction()) {
            AfterTransaction.joined();
        }
        return status;
    }

    /**
     * Whether the transaction is bookkeeping, not a request's change: a {@code TransactionTemplate} named
     * {@link AfterTransaction#BOOKKEEPING}, or an {@code @Transactional} labelled so (the interceptor passes its
     * attribute, whose labels are the annotation's).
     */
    private static boolean bookkeeping(@Nullable TransactionDefinition definition) {
        if (definition == null) {
            return false;
        }
        return AfterTransaction.BOOKKEEPING.equals(definition.getName()) || definition instanceof TransactionAttribute
                attribute && attribute.getLabels().contains(AfterTransaction.BOOKKEEPING);
    }

    @Override
    public void commit(TransactionStatus status) {
        AfterTransaction.Frame frame = AfterTransaction.frameOf(status);
        boolean readOnly = status.isReadOnly();
        Throwable failed = null;
        try {
            delegate.commit(status);
        }
        catch (RuntimeException | Error ex) {
            failed = ex;
        }
        if (frame == null) {
            notOnTheStack(status, "committed");
            rethrow(failed);
            return;
        }
        boolean committed = frame.committed();
        if (committed && !readOnly && !frame.bookkeeping()) {
            AfterTransaction.saved();
        }
        List<AfterTransaction.Failure> failures = AfterTransaction.ended(frame);
        if (failed != null) {
            suppressIn(failed, failures);
            if (committed) {
                // The transaction committed and a callback after the commit threw.
                throw afterTheCommit(failed);
            }
            rethrow(failed);
        }
        if (committed) {
            afterCommit(failures);
        }
        else {
            afterRollback(failures);
        }
    }

    @Override
    public void rollback(TransactionStatus status) {
        AfterTransaction.Frame frame = AfterTransaction.frameOf(status);
        Throwable failed = null;
        try {
            delegate.rollback(status);
        }
        catch (RuntimeException | Error ex) {
            failed = ex;
        }
        if (frame == null) {
            notOnTheStack(status, "rolled back");
            rethrow(failed);
            return;
        }
        List<AfterTransaction.Failure> failures = AfterTransaction.ended(frame);
        if (failed != null) {
            suppressIn(failed, failures);
            rethrow(failed);
        }
        afterRollback(failures);
    }

    /** A status the thread never counted, or one it ended already: nothing to do, but a new one is a mistake. */
    private static void notOnTheStack(TransactionStatus status, String what) {
        if (status.isNewTransaction()) {
            log.error("A transaction was {} that is not open on this thread (ended already, or another thread's): the "
                    + "thread's transactions and kept work are left as they are", what,
                    new IllegalStateException("not on the thread's stack"));
        }
    }

    private static void afterCommit(List<AfterTransaction.Failure> failures) {
        if (failures.isEmpty()) {
            return;
        }
        Throwable first = failures.getFirst().thrown();
        suppressIn(first, failures.subList(1, failures.size()));
        throw afterTheCommit(first);
    }

    /** A failure after a commit: a {@link FailedAfterCommit} after a saved change; an Error as it is, marked. */
    private static RuntimeException afterTheCommit(Throwable thrown) {
        if (thrown instanceof Error error) {
            if (AfterTransaction.savedInThisWork()) {
                error.addSuppressed(FailedAfterCommit.marker());
            }
            throw error;
        }
        if (!AfterTransaction.savedInThisWork() || thrown instanceof FailedAfterCommit) {
            return runtime(thrown);
        }
        return new FailedAfterCommit(thrown);
    }

    private static void afterRollback(List<AfterTransaction.Failure> failures) {
        Throwable thrown = null;
        for (AfterTransaction.Failure failure : failures) {
            if (thrown == null && (failure.failsTheCall() || failure.thrown() instanceof Error)) {
                thrown = failure.thrown();
            }
            else if (thrown != null) {
                thrown.addSuppressed(failure.thrown());
            }
            else {
                log.error("Work kept for after a transaction that rolled back failed", failure.thrown());
            }
        }
        rethrow(thrown);
    }

    private static void suppressIn(Throwable thrown, Collection<AfterTransaction.Failure> failures) {
        for (AfterTransaction.Failure failure : failures) {
            thrown.addSuppressed(failure.thrown());
        }
    }

    private static void rethrow(@Nullable Throwable thrown) {
        if (thrown instanceof Error error) {
            throw error;
        }
        if (thrown != null) {
            throw runtime(thrown);
        }
    }

    private static RuntimeException runtime(Throwable thrown) {
        return thrown instanceof RuntimeException runtime ? runtime : new IllegalStateException(thrown);
    }

    @Override
    public void setTransactionExecutionListeners(Collection<TransactionExecutionListener> listeners) {
        this.listeners.setTransactionExecutionListeners(listeners);
    }

    @Override
    public Collection<TransactionExecutionListener> getTransactionExecutionListeners() {
        return listeners.getTransactionExecutionListeners();
    }
}
