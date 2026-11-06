package com.flowtivo.tinfirm.governance.internal;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@code spend_settlement}, {@code run_spend} and {@code company_spend_alert} (the package 6 contract, §3.1, §3.2).
 * Every method runs in the caller's tenant context and transaction, but {@link #due}, which reads across companies
 * through the {@code SECURITY DEFINER} lookup {@code spend_due}.
 */
@Repository
class SpendStore {

    private final JdbcClient jdbc;

    SpendStore(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** A run due for settlement, across companies. */
    record Due(UUID companyId, UUID runId) {
    }

    /** A settlement row. */
    record Settlement(UUID runId, UUID companyId, UUID payerPrincipalId, Instant startedAt, Instant endedAt,
            String state, int attempts, Instant dueAt, @Nullable Instant leaseUntil, @Nullable String lastError,
            @Nullable Instant failingSince) {
    }

    /** One row of {@code run_spend}. */
    record SpendRow(UUID companyId, UUID runId, String model, UUID conversationId, UUID agentId,
            @Nullable UUID triggerId, UUID payerPrincipalId, UUID actingPrincipalId, boolean personal,
            Instant endedAt, long requests, long inputTokens, long outputTokens, long cachedInputTokens,
            long cacheWriteTokens, @Nullable BigDecimal costUsd, String source, Instant recordedAt) {
    }

    // ---- settlements ------------------------------------------------------------------------------------------------

    /**
     * A run to settle from LiteLLM's logs once {@code dueAt} passed. A run that ends again (a loss by lease expiry,
     * then resumed) moves its end and due time while it is still pending.
     */
    void pending(UUID companyId, UUID runId, UUID payerPrincipalId, Instant startedAt, Instant endedAt,
            Instant dueAt) {
        jdbc.sql("""
                INSERT INTO spend_settlement (run_id, company_id, payer_principal_id, started_at, ended_at, state,
                                              due_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                ON CONFLICT (run_id) DO UPDATE
                   SET ended_at = greatest(spend_settlement.ended_at, excluded.ended_at), due_at = excluded.due_at
                 WHERE spend_settlement.state = 'PENDING'""")
            .params(runId, companyId, payerPrincipalId, ts(startedAt), ts(endedAt), ts(dueAt))
            .update();
    }

    /**
     * A request the relay counted after the run had ended: the run gets its {@code PENDING} settlement when it has
     * none yet (its end saw no request: its only answer was still under way), and a pending one is due again at
     * {@code dueAt}, since the answer's log is still to be written, and loses the lease of a worker holding it, which
     * read the relay's count before the answer and settles nothing from it. A settled one stays as it is.
     */
    void pendingOrDue(UUID companyId, UUID runId, UUID payerPrincipalId, Instant startedAt, Instant endedAt,
            Instant dueAt) {
        jdbc.sql("""
                INSERT INTO spend_settlement (run_id, company_id, payer_principal_id, started_at, ended_at, state,
                                              due_at)
                VALUES (?, ?, ?, ?, ?, 'PENDING', ?)
                ON CONFLICT (run_id) DO UPDATE SET due_at = excluded.due_at, lease_until = NULL
                 WHERE spend_settlement.state = 'PENDING'""")
            .params(runId, companyId, payerPrincipalId, ts(startedAt), ts(endedAt), ts(dueAt))
            .update();
    }

    /** A run settled at once (a subscription's); false when it was settled before. */
    boolean settledAtOnce(UUID companyId, UUID runId, UUID payerPrincipalId, Instant startedAt, Instant endedAt,
            Instant now) {
        return jdbc.sql("""
                INSERT INTO spend_settlement (run_id, company_id, payer_principal_id, started_at, ended_at, state,
                                              due_at, settled_at)
                VALUES (?, ?, ?, ?, ?, 'SETTLED', ?, ?)
                ON CONFLICT (run_id) DO NOTHING""")
            .params(runId, companyId, payerPrincipalId, ts(startedAt), ts(endedAt), ts(now), ts(now))
            .update() == 1;
    }

    /** The due settlements across companies, oldest first; only through {@code spend_due}, ids only. */
    List<Due> due(Instant now, int limit) {
        return jdbc.sql("SELECT company_id, run_id FROM spend_due(?, ?)")
            .params(ts(now), limit)
            .query((rs, row) -> new Due(rs.getObject("company_id", UUID.class), rs.getObject("run_id", UUID.class)))
            .list();
    }

    /**
     * Claims the company's due settlements among {@code runIds} that no other worker holds, with a lease until
     * {@code leaseUntil}: one worker reads a run's logs at a time (G3). The claim takes a transaction-scoped advisory
     * lock of the company first, so that one worker claims a company's due runs at a time, all of them: two workers
     * never split one company's runs and read the same key's logs twice. A worker that does not get the lock claims
     * nothing this round.
     */
    List<Settlement> claim(UUID companyId, Collection<UUID> runIds, Instant now, Instant leaseUntil) {
        if (runIds.isEmpty()) {
            return List.of();
        }
        Boolean locked = jdbc.sql("SELECT pg_try_advisory_xact_lock(hashtextextended('spend:' || ?, 0))")
            .param(companyId.toString())
            .query(Boolean.class)
            .single();
        if (!Boolean.TRUE.equals(locked)) {
            return List.of();
        }
        return jdbc.sql("""
                UPDATE spend_settlement s SET lease_until = :lease
                  FROM (SELECT run_id FROM spend_settlement
                         WHERE company_id = :company AND run_id IN (:runs) AND state = 'PENDING' AND due_at <= :now
                           AND (lease_until IS NULL OR lease_until < :now)
                         ORDER BY run_id
                           FOR UPDATE SKIP LOCKED) c
                 WHERE s.run_id = c.run_id
                RETURNING s.*""")
            .param("lease", ts(leaseUntil))
            .param("company", companyId)
            .param("runs", runIds)
            .param("now", ts(now))
            .query(SpendStore::settlement)
            .list();
    }

    /** The settlement, locked, while it is pending under the lease this worker took. */
    Optional<Settlement> lockClaimed(UUID runId, Instant leaseUntil) {
        return jdbc.sql("""
                SELECT * FROM spend_settlement WHERE run_id = ? AND state = 'PENDING' AND lease_until = ?
                   FOR UPDATE""")
            .params(runId, ts(leaseUntil))
            .query(SpendStore::settlement)
            .optional();
    }

    void settle(UUID runId, Instant now) {
        jdbc.sql("""
                UPDATE spend_settlement SET state = 'SETTLED', settled_at = ?, lease_until = NULL, last_error = NULL,
                                            failing_since = NULL
                 WHERE run_id = ?""")
            .params(ts(now), runId)
            .update();
    }

    /** Tried again at {@code dueAt}; {@code error} is why LiteLLM's logs could not be read, else null. */
    void retry(UUID runId, int attempts, Instant dueAt, @Nullable String error, Instant now) {
        String shown = error != null && error.length() > 500 ? error.substring(0, 500) : error;
        jdbc.sql("""
                UPDATE spend_settlement
                   SET attempts = ?, due_at = ?, lease_until = NULL, last_error = ?,
                       failing_since = CASE WHEN CAST(? AS varchar) IS NULL THEN NULL
                                            ELSE coalesce(failing_since, ?) END
                 WHERE run_id = ?""")
            .params(attempts, ts(dueAt), shown, shown, ts(now), runId)
            .update();
    }

    /** The company's pending runs whose logs could not be read since before {@code before}: the oldest such. */
    Optional<Settlement> failingSince(UUID companyId, Instant before) {
        return jdbc.sql("""
                SELECT * FROM spend_settlement
                 WHERE company_id = ? AND state = 'PENDING' AND failing_since <= ?
                 ORDER BY failing_since LIMIT 1""")
            .params(companyId, ts(before))
            .query(SpendStore::settlement)
            .optional();
    }

    /** Settlements settled before {@code settledBefore} and the company's alerts crossed before the cutoff go. */
    void sweep(UUID companyId, Instant settledBefore, Instant alertsBefore) {
        jdbc.sql("DELETE FROM spend_settlement WHERE company_id = ? AND state = 'SETTLED' AND settled_at < ?")
            .params(companyId, ts(settledBefore))
            .update();
        jdbc.sql("DELETE FROM company_spend_alert WHERE company_id = ? AND crossed_at < ?")
            .params(companyId, ts(alertsBefore))
            .update();
    }

    // ---- run_spend --------------------------------------------------------------------------------------------------

    /** Writes a run's spend on one model once (G3): a second write of the same run and model changes nothing. */
    boolean insert(SpendRow row) {
        return jdbc.sql("""
                INSERT INTO run_spend (company_id, run_id, model, conversation_id, agent_id, trigger_id,
                                       payer_principal_id, acting_principal_id, personal, day, ended_at, requests,
                                       input_tokens, output_tokens, cached_input_tokens, cache_write_tokens, cost_usd,
                                       source, recorded_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (run_id, model) DO NOTHING""")
            .params(row.companyId(), row.runId(), row.model(), row.conversationId(), row.agentId(), row.triggerId(),
                    row.payerPrincipalId(), row.actingPrincipalId(), row.personal(),
                    row.endedAt().atOffset(ZoneOffset.UTC).toLocalDate(), ts(row.endedAt()), row.requests(),
                    row.inputTokens(), row.outputTokens(), row.cachedInputTokens(), row.cacheWriteTokens(),
                    row.costUsd(), row.source(), ts(row.recordedAt()))
            .update() == 1;
    }

    /** The company's cost of the runs that ended in {@code [from, to)}, by UTC day. */
    BigDecimal cost(UUID companyId, LocalDate from, LocalDate to) {
        return jdbc.sql("""
                SELECT coalesce(sum(cost_usd), 0) FROM run_spend WHERE company_id = ? AND day >= ? AND day < ?""")
            .params(companyId, from, to)
            .query(BigDecimal.class)
            .single();
    }

    /** How many of the company's runs ending on the day were settled from the relay's count. */
    int relayRuns(UUID companyId, LocalDate day) {
        return jdbc.sql("""
                SELECT count(DISTINCT run_id) FROM run_spend WHERE company_id = ? AND day = ? AND source = 'RELAY'""")
            .params(companyId, day)
            .query(Integer.class)
            .single();
    }

    /** What the principal paid for runs that ended in {@code [from, to)}. */
    Totals paidBy(UUID companyId, UUID principalId, Instant from, Instant to) {
        return jdbc.sql("""
                SELECT count(DISTINCT run_id) AS runs, coalesce(sum(requests), 0) AS requests,
                       coalesce(sum(input_tokens), 0) AS input_tokens, coalesce(sum(output_tokens), 0) AS output_tokens,
                       coalesce(sum(cost_usd), 0) AS cost_usd,
                       coalesce(sum(input_tokens + output_tokens + cached_input_tokens + cache_write_tokens)
                                FILTER (WHERE source = 'SUBSCRIPTION'), 0) AS subscription_tokens
                  FROM run_spend
                 WHERE company_id = ? AND payer_principal_id = ? AND ended_at >= ? AND ended_at < ?""")
            .params(companyId, principalId, ts(from), ts(to))
            .query((rs, row) -> new Totals(rs.getLong("runs"), rs.getLong("requests"), rs.getLong("input_tokens"),
                    rs.getLong("output_tokens"), rs.getBigDecimal("cost_usd"), rs.getLong("subscription_tokens")))
            .single();
    }

    record Totals(long runs, long requests, long inputTokens, long outputTokens, BigDecimal costUsd,
            long subscriptionTokens) {
    }

    // ---- company_spend_alert ----------------------------------------------------------------------------------------

    /** Records the month's crossing once; false when it was recorded before. */
    boolean companyAlert(UUID companyId, LocalDate month, BigDecimal totalUsd, BigDecimal alertUsd, Instant now) {
        return jdbc.sql("""
                INSERT INTO company_spend_alert (company_id, month, crossed_at, total_usd, alert_usd)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (company_id, month) DO NOTHING""")
            .params(companyId, month, ts(now), totalUsd, alertUsd)
            .update() == 1;
    }

    record CompanyAlert(LocalDate month, Instant crossedAt, BigDecimal totalUsd, BigDecimal alertUsd) {
    }

    Optional<CompanyAlert> companyAlert(UUID companyId, LocalDate month) {
        return jdbc.sql("""
                SELECT month, crossed_at, total_usd, alert_usd FROM company_spend_alert
                 WHERE company_id = ? AND month = ?""")
            .params(companyId, month)
            .query((rs, row) -> new CompanyAlert(rs.getObject("month", LocalDate.class),
                    rs.getObject("crossed_at", OffsetDateTime.class).toInstant(), rs.getBigDecimal("total_usd"),
                    rs.getBigDecimal("alert_usd")))
            .optional();
    }

    // ---- helpers ----------------------------------------------------------------------------------------------------

    private static Settlement settlement(ResultSet rs, int row) throws SQLException {
        return new Settlement(rs.getObject("run_id", UUID.class), rs.getObject("company_id", UUID.class),
                rs.getObject("payer_principal_id", UUID.class), instant(rs, "started_at"), instant(rs, "ended_at"),
                rs.getString("state"), rs.getInt("attempts"), instant(rs, "due_at"), nullableInstant(rs, "lease_until"),
                rs.getString("last_error"), nullableInstant(rs, "failing_since"));
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, OffsetDateTime.class).toInstant();
    }

    private static @Nullable Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value != null ? value.toInstant() : null;
    }

    static OffsetDateTime ts(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }
}
