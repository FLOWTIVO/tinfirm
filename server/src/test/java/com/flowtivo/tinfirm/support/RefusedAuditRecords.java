package com.flowtivo.tinfirm.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * TEST FIXTURE: until closed, the database refuses every audit record of one action and one actor, as a record that
 * cannot be written (a trigger on {@code audit_event}, made by the schema's owner). What is refused is a row of a table
 * that the trigger's function reads, written with bind parameters: no statement here is built from a value. The table,
 * the function and the trigger go when the last fixture closes; a fixture is made and closed in one transaction each,
 * under one advisory lock, so that two fixtures never make and remove those shared objects at once.
 */
public record RefusedAuditRecords(UUID id) implements AutoCloseable {

    /** Held to the end of the transaction that makes or closes a fixture. */
    private static final String ONE_AT_A_TIME = "SELECT pg_advisory_xact_lock(hashtext('test_refused_audit'))";

    public static RefusedAuditRecords of(String action, UUID actorId) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection owner = TestDatabase.connectAsOwner()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute(ONE_AT_A_TIME);
                statement.execute("""
                        CREATE TABLE IF NOT EXISTS test_refused_audit (
                            id uuid PRIMARY KEY, action text NOT NULL, actor_id text NOT NULL)""");
                statement.execute("GRANT SELECT ON test_refused_audit TO PUBLIC");
                statement.execute("""
                        CREATE OR REPLACE FUNCTION test_refuse_audit() RETURNS trigger LANGUAGE plpgsql AS $$
                        BEGIN
                            IF EXISTS (SELECT 1 FROM test_refused_audit refused
                                    WHERE refused.action = NEW.action::text
                                      AND refused.actor_id = NEW.actor_id::text) THEN
                                RAISE EXCEPTION 'refused by the test';
                            END IF;
                            RETURN NEW;
                        END $$""");
                statement.execute("""
                        CREATE OR REPLACE TRIGGER test_refuse_audit BEFORE INSERT ON audit_event
                            FOR EACH ROW EXECUTE FUNCTION test_refuse_audit()""");
            }
            try (PreparedStatement insert = owner
                .prepareStatement("INSERT INTO test_refused_audit (id, action, actor_id) VALUES (?, ?, ?)")) {
                insert.setObject(1, id);
                insert.setString(2, action);
                insert.setString(3, actorId.toString());
                insert.executeUpdate();
            }
            owner.commit();
        }
        return new RefusedAuditRecords(id);
    }

    @Override
    public void close() throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner()) {
            owner.setAutoCommit(false);
            try (Statement statement = owner.createStatement()) {
                statement.execute(ONE_AT_A_TIME);
            }
            try (PreparedStatement delete = owner.prepareStatement("DELETE FROM test_refused_audit WHERE id = ?")) {
                delete.setObject(1, id);
                delete.executeUpdate();
            }
            try (Statement statement = owner.createStatement()) {
                boolean last;
                try (ResultSet rows = statement.executeQuery("SELECT NOT EXISTS (SELECT 1 FROM test_refused_audit)")) {
                    rows.next();
                    last = rows.getBoolean(1);
                }
                if (last) {
                    statement.execute("DROP TRIGGER IF EXISTS test_refuse_audit ON audit_event");
                    statement.execute("DROP FUNCTION IF EXISTS test_refuse_audit()");
                    statement.execute("DROP TABLE IF EXISTS test_refused_audit");
                }
            }
            owner.commit();
        }
    }
}
