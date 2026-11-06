package com.flowtivo.tinfirm.agent.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL access to {@code memory_settings} (V045), under row-level security. */
@Repository
class MemorySettingsStore {

    private final JdbcClient jdbc;

    MemorySettingsStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The model that tidies the company's memories; empty for none. */
    Optional<String> compactionModel(UUID companyId) {
        return jdbc.sql("SELECT compaction_model FROM memory_settings WHERE company_id = ?")
            .param(companyId)
            .query(String.class)
            .optional();
    }

    /** Chooses the model, or none. */
    void setCompactionModel(UUID companyId, @Nullable String model, Instant at, @Nullable UUID by) {
        jdbc.sql("""
                INSERT INTO memory_settings (company_id, compaction_model, updated_at, updated_by)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (company_id) DO UPDATE
                   SET compaction_model = EXCLUDED.compaction_model, updated_at = EXCLUDED.updated_at,
                       updated_by = EXCLUDED.updated_by""")
            .params(companyId, model, at.atOffset(ZoneOffset.UTC), by)
            .update();
    }
}
