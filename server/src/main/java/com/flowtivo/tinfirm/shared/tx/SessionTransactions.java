package com.flowtivo.tinfirm.shared.tx;

import java.sql.SQLTransientConnectionException;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Spring Session's transactions ({@link TransactionManagerConfiguration}), each of its own. Spring Session's JDBC
 * repository runs its writes, a session's save and its deletion, with {@link #executeWithoutResult}, and its reads, the
 * lookup of a session by its id or by its principal and the clean-up of the expired ones, with {@link #execute}
 * (spring-session-jdbc 4.x; {@code SessionTransactionsTest} pins it). Once the request asked the pool in vain, its
 * session's bookkeeping asks no more: each asking is a wait of up to
 * {@code SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT}, and a busy answer would come several of them late.
 * <ul>
 * <li>Once the request is answered by the rule of the busy answer ({@link AfterTransaction#answeredBusy()}), a
 * write is skipped quietly: the save that Spring Session makes when the busy answer is committed, and the one at the
 * end of its filter, so that the answer is written in the ordinary way. What is not saved is the session's last
 * access and any change of it in this failed request: a sign-in answered busy is signed in again.</li>
 * <li>A lookup is never skipped: it fails at once in the pool's way. An answer of "no session" would let a later
 * {@code getSession(true)} make a new session, whose cookie would take the place of the browser's.</li>
 * <li>Once one of these transactions found no connection, and the request is not answered yet, every one of them
 * fails at once in the pool's way: the request is failing for want of a connection.</li>
 * </ul>
 */
final class SessionTransactions extends TransactionTemplate {

    private static final long serialVersionUID = 1L;

    SessionTransactions(PlatformTransactionManager transactionManager) {
        super(transactionManager);
    }

    @Override
    public <T extends @Nullable Object> T execute(TransactionCallback<T> action) throws TransactionException {
        if (AfterTransaction.answeredBusy() || AfterTransaction.sessionFoundNoConnection()) {
            throw notAsked();
        }
        try {
            return super.execute(action);
        }
        catch (CannotCreateTransactionException ex) {
            AfterTransaction.sessionFoundNoConnection(noConnection(ex));
            throw ex;
        }
    }

    @Override
    public void executeWithoutResult(Consumer<TransactionStatus> action) throws TransactionException {
        if (AfterTransaction.answeredBusy()) {
            return;
        }
        super.executeWithoutResult(action);
    }

    private static CannotCreateTransactionException notAsked() {
        return new CannotCreateTransactionException("Spring Session's transaction was not begun: the request already "
                + "found no free database connection",
                new SQLTransientConnectionException("No connection asked for: the request already found none"));
    }

    /** Whether the pool gave no connection in time: its {@link SQLTransientConnectionException} among the causes. */
    private static boolean noConnection(Throwable thrown) {
        int depth = 0;
        for (Throwable cause = thrown; cause != null && depth < 32; cause = cause.getCause(), depth++) {
            if (cause instanceof SQLTransientConnectionException) {
                return true;
            }
        }
        return false;
    }
}
