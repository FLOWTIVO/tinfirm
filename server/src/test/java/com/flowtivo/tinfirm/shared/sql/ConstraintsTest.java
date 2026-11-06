package com.flowtivo.tinfirm.shared.sql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.BatchUpdateException;
import java.sql.SQLException;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

/**
 * The constraint a refusal names is read from PostgreSQL's error ({@code constraint}, the protocol's field
 * {@code n}), along the exception's causes, never from its message's text.
 */
class ConstraintsTest {

    /** PostgreSQL's error as the driver reads it from the wire: its fields, each a type and a value. */
    private static PSQLException refused(@Nullable String constraint) {
        String fields = "SERROR\0VERROR\0C23505\0Mduplicate key value violates unique constraint\0"
                + (constraint != null ? "n" + constraint + "\0" : "");
        return new PSQLException(new ServerErrorMessage(fields));
    }

    @Test
    void aRefusalNamesItsConstraint() {
        PSQLException refusal = refused("trigger_name_uq");
        assertThat(Constraints.nameOf(refusal)).isEqualTo("trigger_name_uq");
        assertThat(Constraints.violated(refusal, "trigger_name_uq")).isTrue();
        assertThat(Constraints.violated(refusal, "trigger_channel_agent_uq", "trigger_name_uq")).isTrue();
        assertThat(Constraints.violated(refusal, "trigger_channel_agent_uq")).isFalse();
        assertThat(Constraints.violated(refusal)).isFalse();
    }

    @Test
    void theRefusalIsFoundAlongTheCausesAndABatchsNextException() {
        DuplicateKeyException spring = new DuplicateKeyException("PreparedStatementCallback; duplicate key",
                refused("agent_handle_uq"));
        assertThat(Constraints.nameOf(spring)).isEqualTo("agent_handle_uq");
        RuntimeException nested = new IllegalStateException("the save failed",
                new DataIntegrityViolationException("could not execute statement", spring));
        assertThat(Constraints.violated(nested, "agent_name_uq", "agent_handle_uq")).isTrue();

        BatchUpdateException batch = new BatchUpdateException("Batch entry 0 was aborted", new int[0]);
        batch.setNextException(refused("trigger_channel_default_uq"));
        assertThat(Constraints.nameOf(new DataIntegrityViolationException("batch", batch)))
            .isEqualTo("trigger_channel_default_uq");

        // A batch's exception with a cause of its own that names nothing: the next exception is still read.
        BatchUpdateException withCause = new BatchUpdateException("Batch entry 0 was aborted", new int[0],
                new SQLException("the statement was aborted"));
        withCause.setNextException(refused("trigger_name_uq"));
        assertThat(Constraints.nameOf(new DataIntegrityViolationException("batch", withCause)))
            .isEqualTo("trigger_name_uq");
    }

    @Test
    void aMessageThatMentionsTheNameIsNoRefusalOfItAndOtherExceptionsNameNothing() {
        SQLException worded = new SQLException("ERROR: duplicate key value violates unique constraint "
                + "\"trigger_name_uq\"", "23505");
        DataIntegrityViolationException byText = new DataIntegrityViolationException(
                "violates unique constraint \"trigger_name_uq\"", worded);
        assertThat(Constraints.nameOf(byText)).isNull();
        assertThat(Constraints.violated(byText, "trigger_name_uq")).isFalse();

        // A PostgreSQL error without a constraint, and one the driver raised itself, without the server's error.
        assertThat(Constraints.nameOf(refused(null))).isNull();
        assertThat(Constraints.nameOf(new PSQLException("I/O error", PSQLState.CONNECTION_FAILURE))).isNull();
        assertThat(Constraints.nameOf(new IllegalStateException("not the database's"))).isNull();
        assertThat(Constraints.nameOf(null)).isNull();
        assertThat(Constraints.violated(null, "trigger_name_uq")).isFalse();
    }

    @Test
    void aCycleOfCausesEnds() {
        RuntimeException first = new RuntimeException("first");
        RuntimeException second = new RuntimeException("second", first);
        first.initCause(second);
        assertThat(Constraints.nameOf(second)).isNull();
    }
}
