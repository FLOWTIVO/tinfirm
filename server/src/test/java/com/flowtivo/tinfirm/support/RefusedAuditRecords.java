package com.flowtivo.tinfirm.support;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * TEST FIXTURE: until closed, the database refuses every audit record of one action and one actor, as a record that
 * cannot be written (a trigger on {@code audit_event}, made by the schema's owner).
 */
public record RefusedAuditRecords(String name) implements AutoCloseable {

    public static RefusedAuditRecords of(String action, UUID actorId) throws SQLException {
        String name = "refuse_audit_" + Long.toHexString(System.nanoTime());
        try (Connection owner = TestDatabase.connectAsOwner(); Statement statement = owner.createStatement()) {
            statement.execute("CREATE FUNCTION " + name + "() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN "
                    + "IF NEW.action = '" + action + "' AND NEW.actor_id = '" + actorId + "' THEN "
                    + "RAISE EXCEPTION 'refused by the test'; END IF; RETURN NEW; END $$");
            statement.execute("CREATE TRIGGER " + name + " BEFORE INSERT ON audit_event FOR EACH ROW EXECUTE "
                    + "FUNCTION " + name + "()");
        }
        return new RefusedAuditRecords(name);
    }

    @Override
    public void close() throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner(); Statement statement = owner.createStatement()) {
            statement.execute("DROP TRIGGER " + name + " ON audit_event");
            statement.execute("DROP FUNCTION " + name + "()");
        }
    }
}
