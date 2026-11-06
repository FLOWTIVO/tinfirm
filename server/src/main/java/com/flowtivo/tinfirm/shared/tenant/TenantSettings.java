package com.flowtivo.tinfirm.shared.tenant;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Writes a {@link TenantContext} into the current database transaction with transaction-local
 * {@code set_config(..., true)}, and reads it back. The values vanish at commit or rollback, so a pooled connection
 * never carries one request's context into another.
 */
final class TenantSettings {

    private static final String SQL = """
            SELECT set_config('app.company_id', ?, true),
                   set_config('app.user_id', ?, true),
                   set_config('app.instance_admin', ?, true),
                   set_config('app.system', ?, true)""";

    private TenantSettings() {
    }

    static void apply(Connection connection, TenantContext context) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(SQL)) {
            statement.setString(1, text(context.companyId()));
            statement.setString(2, text(context.userId()));
            statement.setString(3, Boolean.toString(context.instanceAdmin()));
            statement.setString(4, Boolean.toString(context.system()));
            statement.execute();
        }
    }

    private static final String READ = """
            SELECT current_setting('app.company_id', true), current_setting('app.user_id', true),
                   current_setting('app.instance_admin', true), current_setting('app.system', true)""";

    /** The context the transaction's row-level security sees now. */
    static TenantContext read(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(READ);
                ResultSet row = statement.executeQuery()) {
            row.next();
            return new TenantContext(id(row.getString(2)), "true".equals(row.getString(3)), id(row.getString(1)),
                    "true".equals(row.getString(4)));
        }
    }

    private static @Nullable UUID id(@Nullable String text) {
        return text == null || text.isEmpty() ? null : UUID.fromString(text);
    }

    private static String text(@Nullable UUID id) {
        return id != null ? id.toString() : "";
    }
}
