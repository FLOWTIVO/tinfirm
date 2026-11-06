package com.flowtivo.tinfirm.model.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.flowtivo.tinfirm.model.ModelRelay;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * {@code relay_usage} (the package 6 contract, §3.1): the relay's own count of each run's answers, per model and key.
 * Each metered answer ({@link RelayUsageMeter.Answered}, published by the relay after the answer ended, outside any
 * transaction) is added in a short transaction of the run's company; a failure loses that answer's count and is
 * logged. Rows are deleted 30 days after their last answer, with the reconciliation of the company's keys.
 */
@Repository
class RelayUsageStore implements KeyReconciliation.CompanyCleanup {

    private static final Logger log = LoggerFactory.getLogger(RelayUsageStore.class);

    private static final Duration KEEP = Duration.ofDays(30);

    private final JdbcClient jdbc;

    private final SystemTransactions transactions;

    private final TimeSource time;

    RelayUsageStore(JdbcClient jdbc, SystemTransactions transactions, TimeSource time) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.time = time;
    }

    @EventListener
    void answered(RelayUsageMeter.Answered answer) {
        try {
            transactions.runInCompany(answer.companyId(), () -> add(answer));
        }
        catch (RuntimeException ex) {
            log.warn("The usage of a relayed answer of run {} was not counted", answer.runId(), ex);
        }
    }

    void add(RelayUsageMeter.Answered answer) {
        jdbc.sql("""
                INSERT INTO relay_usage (company_id, run_id, model, key_id, requests, input_tokens, output_tokens,
                                         cached_input_tokens, cache_write_tokens, updated_at)
                VALUES (?, ?, ?, ?, 1, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, model, key_id) DO UPDATE
                   SET requests = relay_usage.requests + 1,
                       input_tokens = relay_usage.input_tokens + excluded.input_tokens,
                       output_tokens = relay_usage.output_tokens + excluded.output_tokens,
                       cached_input_tokens = relay_usage.cached_input_tokens + excluded.cached_input_tokens,
                       cache_write_tokens = relay_usage.cache_write_tokens + excluded.cache_write_tokens,
                       updated_at = excluded.updated_at""")
            .params(answer.companyId(), answer.runId(), answer.model(), answer.keyId(), answer.inputTokens(),
                    answer.outputTokens(), answer.cachedInputTokens(), answer.cacheWriteTokens(),
                    time.now().atOffset(ZoneOffset.UTC))
            .update();
    }

    List<ModelRelay.ModelUsage> ofRun(UUID companyId, UUID runId) {
        return jdbc.sql("""
                SELECT model, key_id, requests, input_tokens, output_tokens, cached_input_tokens, cache_write_tokens
                  FROM relay_usage WHERE company_id = ? AND run_id = ? ORDER BY model, key_id""")
            .params(companyId, runId)
            .query((rs, row) -> new ModelRelay.ModelUsage(rs.getString("model"), rs.getString("key_id"),
                    rs.getLong("requests"), rs.getLong("input_tokens"), rs.getLong("output_tokens"),
                    rs.getLong("cached_input_tokens"), rs.getLong("cache_write_tokens")))
            .list();
    }

    @Override
    public void cleanUp(UUID companyId) {
        Instant before = time.now().minus(KEEP);
        transactions.runInCompany(companyId, () -> jdbc
            .sql("DELETE FROM relay_usage WHERE company_id = ? AND updated_at < ?")
            .params(companyId, before.atOffset(ZoneOffset.UTC))
            .update());
    }
}
