package com.flowtivo.tinfirm.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * Budget alerts (the package 6 contract, §3.2, §5.3): after each sync that read a key's spend, a threshold of the
 * company's {@code budget_alert_thresholds} the period's spend crossed is recorded once per period
 * ({@code budget_alert}). Who is told is the inbox's to compute (the governance module). Runs in the caller's tenant
 * context and transaction.
 */
public interface BudgetAlerts {

    /**
     * The company's alerts that still stand: their period has not reset, and the spend is still at the threshold of
     * the budget as it is now (a raised budget ends them). Newest first.
     */
    List<Alert> open(UUID companyId);

    /** The company's monthly alert amount (open point 18), when admins set one. */
    Optional<BigDecimal> monthlyAlertUsd(UUID companyId);

    /**
     * A threshold crossed in the current period.
     *
     * @param spentUsd  the period's spend as LiteLLM last said it
     * @param budgetUsd the principal's budget now
     * @param resetsAt  when the period resets; null when LiteLLM named no time
     */
    record Alert(UUID principalId, int threshold, BigDecimal spentUsd, BigDecimal budgetUsd,
            @Nullable Instant resetsAt, Instant crossedAt) {
    }
}
