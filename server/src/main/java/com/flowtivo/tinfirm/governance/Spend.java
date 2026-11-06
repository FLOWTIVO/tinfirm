package com.flowtivo.tinfirm.governance;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.api.model.SpendDimension;
import com.flowtivo.tinfirm.shared.api.model.SpendReport;
import com.flowtivo.tinfirm.shared.api.model.SpendSource;

/**
 * Spend for other modules (the package 6 contract, §5.7's {@code get_my_spend}). {@link #paidBy} runs in the caller's
 * tenant context and transaction, and who may see it is the caller's to check; {@link #report} is the Spend API's
 * {@code getSpend}, with its scope.
 */
public interface Spend {

    /** What the principal paid for runs that ended in {@code [from, to)}. */
    Totals paidBy(UUID companyId, UUID principalId, Instant from, Instant to);

    /**
     * {@code getSpend} as the caller may see it (G1), in its own transaction unless the caller has one: a member the
     * runs they paid and those of the triggers whose author or team they are; ADMIN and OWNER every run, another
     * person's personal conversations folded (ADMIN) or by key only (OWNER). Not audited, as the API's JSON read is
     * not.
     */
    SpendReport report(UUID companyId, Query query);

    /**
     * Totals of settled runs.
     *
     * @param costUsd            the cost of {@code GATEWAY} and {@code RELAY} rows
     * @param subscriptionTokens the tokens of subscriptions' runs, as their sandboxes reported them
     */
    record Totals(long runs, long requests, long inputTokens, long outputTokens, BigDecimal costUsd,
            long subscriptionTokens) {
    }

    /** What a spend report asks for, as {@code getSpend}'s parameters name it; every one optional. */
    record Query(@Nullable LocalDate from, @Nullable LocalDate to, @Nullable String zone,
            @Nullable List<SpendDimension> groupBy, @Nullable String bucket, @Nullable UUID payerId,
            @Nullable UUID actorId, @Nullable UUID agentId, @Nullable UUID triggerId, @Nullable UUID conversationId,
            @Nullable String model, @Nullable SpendSource source) {
    }
}
