package com.flowtivo.tinfirm.shared.tx;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.PreparedStatementSetter;
import org.springframework.jdbc.core.ResultSetExtractor;
import org.springframework.session.jdbc.JdbcIndexedSessionRepository;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;

import com.flowtivo.tinfirm.shared.web.ServerBusy;

/**
 * {@link SessionTransactions}' rule rests on how Spring Session's JDBC repository uses its transactions: its writes
 * (a save, a deletion) through {@code executeWithoutResult}, its lookups through {@code execute}. Pinned here, so that
 * a new Spring Session that changes it fails this test; and the rule itself once the request was answered busy.
 */
class SessionTransactionsTest {

    @AfterEach
    void endOfWork() {
        AfterTransaction.endOfWork("test");
    }

    /** Records which of its two methods the repository called, and runs nothing. */
    private static final class Recording extends org.springframework.transaction.support.TransactionTemplate {

        private static final long serialVersionUID = 1L;

        final transient List<String> calls = new ArrayList<>();

        Recording() {
            super(mock(PlatformTransactionManager.class));
        }

        @Override
        public <T extends @Nullable Object> T execute(TransactionCallback<T> action) {
            calls.add("execute");
            return action.doInTransaction(new SimpleTransactionStatus());
        }

        @Override
        public void executeWithoutResult(Consumer<TransactionStatus> action) {
            calls.add("executeWithoutResult");
            action.accept(new SimpleTransactionStatus());
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void springSessionLooksUpWithExecuteAndWritesWithExecuteWithoutResult() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.query(anyString(), any(PreparedStatementSetter.class), any(ResultSetExtractor.class)))
            .thenReturn(List.of());
        Recording transactions = new Recording();
        JdbcIndexedSessionRepository repository = new JdbcIndexedSessionRepository(jdbc, transactions);

        repository.findById("a-session");
        assertThat(transactions.calls).containsExactly("execute");
        transactions.calls.clear();

        repository.save(repository.createSession());
        repository.deleteById("a-session");
        assertThat(transactions.calls).containsExactly("executeWithoutResult", "executeWithoutResult");
    }

    @Test
    void onceTheRequestIsAnsweredBusyAWriteIsSkippedAndALookupFails() {
        SessionTransactions transactions = new SessionTransactions(mock(PlatformTransactionManager.class));
        AfterTransaction.answeredBusy(true);

        List<String> ran = new ArrayList<>();
        transactions.executeWithoutResult(status -> ran.add("write"));
        assertThat(ran).isEmpty();
        assertThatThrownBy(() -> transactions.execute(status -> "lookup"))
            .isInstanceOf(CannotCreateTransactionException.class)
            .satisfies(thrown -> assertThat(ServerBusy.timedOut(thrown)).isTrue());
    }
}
