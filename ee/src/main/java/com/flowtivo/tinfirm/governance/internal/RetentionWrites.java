package com.flowtivo.tinfirm.governance.internal;

import java.sql.Types;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.SqlParameterValue;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The writes to {@code retention_settings} and {@code legal_hold} that only a paid feature makes (ADR-0077,
 * decision 1): the OWNER's periods ({@link RetentionChangesImpl}) and a hold set ({@link LegalHoldsImpl}). Under
 * {@code ee/}, beside {@link RetentionStore}, whose reads, sweeps and lifts every edition runs; under row-level
 * security as it is: the company of the tenant context only.
 */
@Repository
class RetentionWrites {

    private final JdbcClient jdbc;

    RetentionWrites(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    void save(UUID companyId, Periods periods, Instant at, UUID userId) {
        jdbc.sql("""
                INSERT INTO retention_settings (company_id, run_events_days, chat_content_days, content_days,
                                                files_days, memory_days, updated_at, updated_by)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (company_id) DO UPDATE
                   SET run_events_days = EXCLUDED.run_events_days, chat_content_days = EXCLUDED.chat_content_days,
                       content_days = EXCLUDED.content_days, files_days = EXCLUDED.files_days,
                       memory_days = EXCLUDED.memory_days, updated_at = EXCLUDED.updated_at,
                       updated_by = EXCLUDED.updated_by""")
            .params(companyId, new SqlParameterValue(Types.INTEGER, periods.runEventsDays()),
                    new SqlParameterValue(Types.INTEGER, periods.chatContentDays()),
                    new SqlParameterValue(Types.INTEGER, periods.contentDays()),
                    new SqlParameterValue(Types.INTEGER, periods.filesDays()),
                    new SqlParameterValue(Types.INTEGER, periods.memoryDays()), RetentionStore.ts(at), userId)
            .update();
    }

    void insertHold(RetentionStore.HoldRow hold) {
        jdbc.sql("""
                INSERT INTO legal_hold (id, company_id, conversation_id, principal_id, reason, set_at, set_by)
                VALUES (?, ?, ?, ?, ?, ?, ?)""")
            .params(hold.id(), hold.companyId(), new SqlParameterValue(Types.OTHER, hold.conversationId()),
                    new SqlParameterValue(Types.OTHER, hold.principalId()), hold.reason(),
                    RetentionStore.ts(hold.setAt()), hold.setBy())
            .update();
    }
}
