package com.flowtivo.tinfirm.model.internal;

import java.math.BigDecimal;
import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.model.BudgetAlerts;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;

/**
 * {@code budget_alert} and the alert settings of {@code model_settings} (the package 6 contract, §3.2, §5.3), and
 * {@link BudgetAlerts}. {@link #check} runs after each sync recorded a key's spend, in its transaction: a threshold the
 * period's spend reached is recorded once per period ({@code resets_at}, {@code 'infinity'} when LiteLLM named none).
 * Rows are deleted 400 days after they were crossed, with the reconciliation of the company's keys.
 */
@Repository
class BudgetAlertStore implements BudgetAlerts, KeyReconciliation.CompanyCleanup {

    /** The thresholds before an admin chose others. */
    static final List<Integer> DEFAULT_THRESHOLDS = List.of(80, 100);

    private static final Duration KEEP = Duration.ofDays(400);

    private final JdbcClient jdbc;

    private final SystemTransactions transactions;

    private final TimeSource time;

    BudgetAlertStore(JdbcClient jdbc, SystemTransactions transactions, TimeSource time) {
        this.jdbc = jdbc;
        this.transactions = transactions;
        this.time = time;
    }

    /** The company's alert settings; the defaults without a settings row. */
    record AlertSettings(List<Integer> thresholds, @Nullable BigDecimal monthlyAlertUsd) {

        AlertSettings {
            thresholds = List.copyOf(thresholds);
        }
    }

    AlertSettings settings(UUID companyId) {
        return jdbc.sql("SELECT budget_alert_thresholds, monthly_alert_usd FROM model_settings WHERE company_id = ?")
            .param(companyId)
            .query((rs, row) -> new AlertSettings(thresholds(rs.getArray("budget_alert_thresholds")),
                    rs.getBigDecimal("monthly_alert_usd")))
            .optional()
            .orElse(new AlertSettings(DEFAULT_THRESHOLDS, null));
    }

    /** Stores the alert settings on the company's settings row, which exists. */
    void setSettings(UUID companyId, AlertSettings settings) {
        jdbc.sql("""
                UPDATE model_settings SET budget_alert_thresholds = CAST(? AS smallint[]), monthly_alert_usd = ?
                 WHERE company_id = ?""")
            .params(arrayText(settings.thresholds()), settings.monthlyAlertUsd(), companyId)
            .update();
    }

    /**
     * Records each threshold the period's spend reached, once per period.
     *
     * @param budgetUsd the principal's budget now; nothing is recorded for a budget of 0
     */
    void check(UUID companyId, UUID principalId, BigDecimal spentUsd, BigDecimal budgetUsd,
            @Nullable Instant resetsAt) {
        if (budgetUsd.signum() <= 0) {
            return;
        }
        for (int threshold : settings(companyId).thresholds()) {
            BigDecimal at = budgetUsd.multiply(BigDecimal.valueOf(threshold)).movePointLeft(2);
            if (spentUsd.compareTo(at) < 0) {
                continue;
            }
            jdbc.sql("""
                    INSERT INTO budget_alert (company_id, principal_id, resets_at, threshold, spent_usd, budget_usd,
                                              crossed_at)
                    VALUES (?, ?, coalesce(?, 'infinity'::timestamptz), ?, ?, ?, ?)
                    ON CONFLICT (principal_id, resets_at, threshold) DO NOTHING""")
                .params(companyId, principalId, resetsAt != null ? resetsAt.atOffset(ZoneOffset.UTC) : null,
                        threshold, spentUsd, budgetUsd, time.now().atOffset(ZoneOffset.UTC))
                .update();
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<Alert> open(UUID companyId) {
        return jdbc.sql("""
                SELECT a.principal_id, a.threshold, k.spent_usd, coalesce(k.budget_usd, s.default_budget_usd) AS budget,
                       k.budget_resets_at, a.crossed_at
                  FROM budget_alert a
                  JOIN principal_model_key k ON k.principal_id = a.principal_id AND k.company_id = a.company_id
                  JOIN model_settings s ON s.company_id = a.company_id
                 WHERE a.company_id = ?
                   AND a.resets_at = coalesce(k.budget_resets_at, 'infinity'::timestamptz)
                   AND (k.budget_resets_at IS NULL OR k.budget_resets_at > ?)
                   AND coalesce(k.budget_usd, s.default_budget_usd) > 0
                   AND k.spent_usd >= coalesce(k.budget_usd, s.default_budget_usd) * a.threshold / 100
                 ORDER BY a.crossed_at DESC, a.principal_id, a.threshold DESC""")
            .params(companyId, time.now().atOffset(ZoneOffset.UTC))
            .query(BudgetAlertStore::alert)
            .list();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BigDecimal> monthlyAlertUsd(UUID companyId) {
        return Optional.ofNullable(settings(companyId).monthlyAlertUsd());
    }

    @Override
    public void cleanUp(UUID companyId) {
        Instant before = time.now().minus(KEEP);
        transactions.runInCompany(companyId, () -> jdbc
            .sql("DELETE FROM budget_alert WHERE company_id = ? AND crossed_at < ?")
            .params(companyId, before.atOffset(ZoneOffset.UTC))
            .update());
    }

    private static Alert alert(ResultSet rs, int row) throws SQLException {
        OffsetDateTime resets = rs.getObject("budget_resets_at", OffsetDateTime.class);
        return new Alert(rs.getObject("principal_id", UUID.class), rs.getInt("threshold"),
                rs.getBigDecimal("spent_usd"), rs.getBigDecimal("budget"),
                resets != null ? resets.toInstant() : null,
                rs.getObject("crossed_at", OffsetDateTime.class).toInstant());
    }

    private static List<Integer> thresholds(@Nullable Array array) throws SQLException {
        List<Integer> values = new ArrayList<>();
        if (array != null) {
            for (Object value : (Object[]) array.getArray()) {
                values.add(((Number) value).intValue());
            }
        }
        return values.isEmpty() ? DEFAULT_THRESHOLDS : values;
    }

    private static String arrayText(List<Integer> values) {
        StringBuilder text = new StringBuilder("{");
        for (int i = 0; i < values.size(); i++) {
            text.append(i > 0 ? "," : "").append(values.get(i));
        }
        return text.append('}').toString();
    }
}
