package com.flowtivo.tinfirm.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Plain SQL as the runtime role, in one company's tenant context, for the tests that prove what the database itself
 * refuses (the package 1 contract, §9: a constraint test per invariant). Each instance holds one connection in one
 * transaction, which {@link #close()} rolls back, so nothing a test writes here stays.
 */
public final class TenantSql implements AutoCloseable {

    private final Connection connection;

    private @Nullable UUID companyId;

    private TenantSql(Connection connection) {
        this.connection = connection;
    }

    /** A transaction as {@code tinfirm_app} in the company's context. */
    public static TenantSql in(UUID companyId) throws SQLException {
        Connection connection = TestDatabase.connectAsApp();
        connection.setAutoCommit(false);
        TenantSql sql = new TenantSql(connection);
        sql.context(companyId);
        return sql;
    }

    /** Switches the tenant context; null leaves none, in which nothing is visible. */
    public void context(@Nullable UUID companyId) throws SQLException {
        this.companyId = companyId;
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT set_config('app.company_id', ?, true), set_config('app.user_id', '', true),
                       set_config('app.instance_admin', 'false', true)""")) {
            statement.setString(1, companyId != null ? companyId.toString() : "");
            statement.execute();
        }
    }

    public int update(String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(sql, params)) {
            return statement.executeUpdate();
        }
    }

    public long count(String sql, Object... params) throws SQLException {
        try (PreparedStatement statement = prepare(sql, params); ResultSet row = statement.executeQuery()) {
            row.next();
            return row.getLong(1);
        }
    }

    /** The first column of every row. */
    public List<Object> column(String sql, Object... params) throws SQLException {
        List<Object> values = new ArrayList<>();
        try (PreparedStatement statement = prepare(sql, params); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getObject(1));
            }
        }
        return values;
    }

    /** Every row, each as its columns by name in the query's order. */
    public List<java.util.Map<String, Object>> rows(String sql, Object... params) throws SQLException {
        List<java.util.Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement statement = prepare(sql, params); ResultSet result = statement.executeQuery()) {
            java.sql.ResultSetMetaData columns = result.getMetaData();
            while (result.next()) {
                java.util.Map<String, Object> row = new java.util.LinkedHashMap<>();
                for (int column = 1; column <= columns.getColumnCount(); column++) {
                    row.put(columns.getColumnLabel(column), result.getObject(column));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /**
     * Runs the statement, which must fail, and answers the constraint or index that refused it (the database's
     * {@code constraint_name}, else its message); the transaction goes on as before the statement.
     */
    public String refused(String sql, Object... params) throws SQLException {
        Savepoint savepoint = connection.setSavepoint();
        try (PreparedStatement statement = prepare(sql, params)) {
            statement.executeUpdate();
        }
        catch (SQLException ex) {
            connection.rollback(savepoint);
            String constraint = ex instanceof org.postgresql.util.PSQLException psql
                    && psql.getServerErrorMessage() != null ? psql.getServerErrorMessage().getConstraint() : null;
            return constraint != null ? constraint : String.valueOf(ex.getMessage());
        }
        fail("The database accepted: " + sql);
        throw new AssertionError();
    }

    /** Runs the statement, which must succeed; the transaction goes on. */
    public void accepted(String sql, Object... params) throws SQLException {
        assertThat(update(sql, params)).as(sql).isPositive();
    }

    /**
     * Commits what the transaction wrote, for a test that sets up state the server then reads; the next transaction
     * keeps the tenant context.
     */
    public void commit() throws SQLException {
        connection.commit();
        context(companyId);
    }

    private PreparedStatement prepare(String sql, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int index = 0; index < params.length; index++) {
            statement.setObject(index + 1, params[index]);
        }
        return statement;
    }

    @Override
    public void close() throws SQLException {
        try {
            connection.rollback();
        }
        finally {
            connection.close();
        }
    }
}
