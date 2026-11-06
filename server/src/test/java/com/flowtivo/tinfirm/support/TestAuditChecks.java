package com.flowtivo.tinfirm.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Daily checks of a company's audit chain written directly, as the runtime role in the company's tenant context (TEST
 * FIXTURE ONLY): for tests that need a check's id without running the check.
 */
public final class TestAuditChecks {

    private TestAuditChecks() {
    }

    /** A failed check of the company's chain ({@code SHORTER}, not acknowledged); answers its id. */
    public static UUID failed(UUID companyId) {
        UUID id = UUID.randomUUID();
        try (Connection connection = TestDatabase.connectAsApp()) {
            connection.setAutoCommit(false);
            try (PreparedStatement context = connection.prepareStatement("""
                    SELECT set_config('app.company_id', ?, true), set_config('app.user_id', '', true),
                           set_config('app.instance_admin', 'false', true)""")) {
                context.setString(1, companyId.toString());
                context.execute();
            }
            try (PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO audit_check (id, company_id, checked_at, ok, from_seq, failure, head_seq, head_hash,
                                             events_checked)
                    VALUES (?, ?, ?, false, 1, 'SHORTER', 1, ?, 0)""")) {
                statement.setObject(1, id);
                statement.setObject(2, companyId);
                statement.setObject(3, Instant.now().atOffset(ZoneOffset.UTC));
                statement.setString(4, "0".repeat(64));
                statement.executeUpdate();
            }
            connection.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        return id;
    }
}
